package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.FoldCursor;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.Phase;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.ReduceCursor;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRunRepository;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 통계 실행 단계에 해당하는 커서만 저장·복원한다.
 * 누적·일별 합산·축약 커서의 부재를 구분한다.
 */
@Repository
public class JdbcDemandStatisticsRunRepository implements DemandStatisticsRunRepository {
    private static final String NO_VEHICLE = "";
    private static final String NO_SLOT = "";

    private static final String SELECT = """
        SELECT /* salmonbus:demand_statistics_run.select */ run_id, quality_revision, phase, data_until, input_until_id, vehicle_cursor, cursor_id,
               CASE WHEN hour_cursor = '-infinity' THEN NULL ELSE hour_cursor END AS hour_cursor,
               stop_cursor, slot_cursor,
               CASE WHEN day_cursor = '-infinity' THEN NULL ELSE day_cursor END AS day_cursor,
               completed_at
        FROM stop_demand_run WHERE route_version_id = ?
        """;

    private static final String UPSERT = """
        INSERT /* salmonbus:demand_statistics_run.upsert */ INTO stop_demand_run(route_version_id, run_id, quality_revision, phase, data_until, input_until_id,
            vehicle_cursor, cursor_id, hour_cursor, stop_cursor, slot_cursor, day_cursor, completed_at)
        VALUES (:version, :runId, :qualityRevision, :phase, :dataUntil, :inputUntilId,
            :vehicleCursor, :cursorId, COALESCE(CAST(:hourCursor AS timestamptz), '-infinity'::timestamptz),
            :stopCursor, :slotCursor, COALESCE(CAST(:dayCursor AS date), '-infinity'::date), :completedAt)
        ON CONFLICT (route_version_id) DO UPDATE SET run_id = EXCLUDED.run_id,
            quality_revision = EXCLUDED.quality_revision, phase = EXCLUDED.phase, data_until = EXCLUDED.data_until,
            input_until_id = EXCLUDED.input_until_id, vehicle_cursor = EXCLUDED.vehicle_cursor,
            cursor_id = EXCLUDED.cursor_id, hour_cursor = EXCLUDED.hour_cursor, stop_cursor = EXCLUDED.stop_cursor,
            slot_cursor = EXCLUDED.slot_cursor, day_cursor = EXCLUDED.day_cursor, completed_at = EXCLUDED.completed_at
        """;

    private final JdbcClient jdbc;

    public JdbcDemandStatisticsRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DemandStatisticsRun> find(final long routeVersionId) {
        return jdbc.sql(SELECT).param(routeVersionId).query((rs, row) -> runOf(routeVersionId, rs)).optional();
    }

    @Override
    public void save(DemandStatisticsRun run) {
        Phase phase = run.phase();
        Optional<FoldCursor> fold = Optional.empty();
        if (phase == Phase.FOLD) {
            fold = run.foldCursor();
        }
        Optional<ReduceCursor> reduce = Optional.empty();
        if (phase == Phase.REDUCE) {
            reduce = run.reduceCursor();
        }
        Optional<ReduceCursor> selectedReduce = reduce;
        jdbc.sql(UPSERT)
            .param("version", run.routeVersionId())
            .param("runId", run.runId())
            .param("qualityRevision", run.qualityRevision())
            .param("phase", phase.name())
            .param("dataUntil", offsetOf(run.dataUntil()))
            .param("inputUntilId", run.inputUntilId())
            .param("vehicleCursor", vehicleCursorOf(run, phase, fold.map(FoldCursor::vehicleId).orElse(NO_VEHICLE)))
            .param("cursorId", inputCursorOf(run, phase))
            .param("hourCursor", fold.map(cursor -> offsetOf(cursor.arrivedHourStart())).orElse(null))
            .param("stopCursor", fold.map(FoldCursor::targetStopOrder)
                .or(() -> selectedReduce.map(ReduceCursor::stopOrder)).orElse(0))
            .param("slotCursor", reduce.map(cursor -> storedTimeSlotOf(cursor.timeSlot())).orElse(NO_SLOT))
            .param("dayCursor", reduce.map(ReduceCursor::arrivalDate).orElse(null))
            .param("completedAt", run.completedAt().map(JdbcDemandStatisticsRunRepository::offsetOf).orElse(null))
            .update();
    }

    private static DemandStatisticsRun runOf(final long routeVersionId, ResultSet rs) throws SQLException {
        Phase phase = Phase.valueOf(rs.getString("phase"));
        OffsetDateTime hour = rs.getObject("hour_cursor", OffsetDateTime.class);
        LocalDate day = rs.getObject("day_cursor", LocalDate.class);
        OffsetDateTime completedAt = rs.getObject("completed_at", OffsetDateTime.class);
        final boolean accumulating = phase == Phase.ACCUMULATE;
        FoldCursor fold = readFoldCursor(rs, phase, hour);
        ReduceCursor reduce = readReduceCursor(rs, phase, day);
        return new DemandStatisticsRun(routeVersionId, rs.getObject("run_id", UUID.class),
            rs.getLong("quality_revision"), phase, rs.getObject("data_until", OffsetDateTime.class).toInstant(),
            rs.getLong("input_until_id"), readVehicleCursor(rs, accumulating),
            readInputCursor(rs, accumulating), fold, reduce,
            instantOrNull(completedAt));
    }

    private static String storedTimeSlotOf(TimeSlot timeSlot) {
        return timeSlot.name().toLowerCase(Locale.ROOT);
    }

    private static TimeSlot timeSlotOf(String stored) {
        return TimeSlot.valueOf(stored.toUpperCase(Locale.ROOT));
    }

    private static OffsetDateTime offsetOf(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String vehicleCursorOf(DemandStatisticsRun run, Phase phase, String foldVehicle) {
        if (phase == Phase.ACCUMULATE) {
            return run.vehicleCursor();
        }
        return foldVehicle;
    }

    private static long inputCursorOf(DemandStatisticsRun run, Phase phase) {
        if (phase == Phase.ACCUMULATE) {
            return run.inputCursor();
        }
        return 0;
    }

    /**
     * FOLD 단계이며 시각이 있을 때만 접기 커서의 나머지 열을 읽는다. 다른 단계나 시각 부재는 null이다.
     */
    private static FoldCursor readFoldCursor(ResultSet rs, Phase phase, OffsetDateTime hour) throws SQLException {
        if (phase == Phase.FOLD && hour != null) {
            return new FoldCursor(rs.getString("vehicle_cursor"), hour.toInstant(), rs.getInt("stop_cursor"));
        }
        return null;
    }

    /**
     * REDUCE 단계이며 날짜가 있을 때만 축약 커서의 나머지 열을 읽는다. 다른 단계나 날짜 부재는 null이다.
     */
    private static ReduceCursor readReduceCursor(ResultSet rs, Phase phase, LocalDate day) throws SQLException {
        if (phase == Phase.REDUCE && day != null) {
            return new ReduceCursor(rs.getInt("stop_cursor"), timeSlotOf(rs.getString("slot_cursor")), day);
        }
        return null;
    }

    private static String readVehicleCursor(ResultSet rs, final boolean accumulating) throws SQLException {
        if (accumulating) {
            return rs.getString("vehicle_cursor");
        }
        return NO_VEHICLE;
    }

    private static long readInputCursor(ResultSet rs, final boolean accumulating) throws SQLException {
        if (accumulating) {
            return rs.getLong("cursor_id");
        }
        return 0;
    }

    private static Instant instantOrNull(OffsetDateTime timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }
}
