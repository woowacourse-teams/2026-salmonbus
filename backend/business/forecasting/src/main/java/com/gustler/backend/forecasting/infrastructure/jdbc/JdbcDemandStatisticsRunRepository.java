package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRunRepository;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.FoldCursor;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.ReduceCursor;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.Phase;
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

@Repository
public class JdbcDemandStatisticsRunRepository implements DemandStatisticsRunRepository {

    private static final String NO_VEHICLE = "";
    private static final String NO_SLOT = "";

    private static final String SELECT = """
        SELECT run_id, quality_revision, phase, data_until, input_until_id, vehicle_cursor, cursor_id,
               CASE WHEN hour_cursor = '-infinity' THEN NULL ELSE hour_cursor END AS hour_cursor,
               stop_cursor, slot_cursor,
               CASE WHEN day_cursor = '-infinity' THEN NULL ELSE day_cursor END AS day_cursor,
               completed_at
        FROM stop_demand_run WHERE route_version_id = ?
        """;

    private static final String UPSERT = """
        INSERT INTO stop_demand_run(route_version_id, run_id, quality_revision, phase, data_until, input_until_id,
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

    public JdbcDemandStatisticsRunRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DemandStatisticsRun> find(final long routeVersionId) {
        return jdbc.sql(SELECT).param(routeVersionId).query((rs, row) -> runOf(routeVersionId, rs)).optional();
    }

    @Override
    public void save(final DemandStatisticsRun run) {
        final Phase phase = run.phase();
        final Optional<FoldCursor> fold = phase == Phase.FOLD ? run.foldCursor() : Optional.empty();
        final Optional<ReduceCursor> reduce = phase == Phase.REDUCE ? run.reduceCursor() : Optional.empty();
        jdbc.sql(UPSERT)
            .param("version", run.routeVersionId())
            .param("runId", run.runId())
            .param("qualityRevision", run.qualityRevision())
            .param("phase", phase.name())
            .param("dataUntil", offsetOf(run.dataUntil()))
            .param("inputUntilId", run.inputUntilId())
            .param("vehicleCursor", phase == Phase.ACCUMULATE ? run.vehicleCursor()
                : fold.map(FoldCursor::vehicleId).orElse(NO_VEHICLE))
            .param("cursorId", phase == Phase.ACCUMULATE ? run.inputCursor() : 0)
            .param("hourCursor", fold.map(cursor -> offsetOf(cursor.arrivedHourStart())).orElse(null))
            .param("stopCursor", fold.map(FoldCursor::targetStopOrder)
                .or(() -> reduce.map(ReduceCursor::stopOrder)).orElse(0))
            .param("slotCursor", reduce.map(cursor -> storedTimeSlotOf(cursor.timeSlot())).orElse(NO_SLOT))
            .param("dayCursor", reduce.map(ReduceCursor::arrivalDate).orElse(null))
            .param("completedAt", run.completedAt().map(JdbcDemandStatisticsRunRepository::offsetOf).orElse(null))
            .update();
    }

    private static DemandStatisticsRun runOf(final long routeVersionId, final ResultSet rs) throws SQLException {
        final Phase phase = Phase.valueOf(rs.getString("phase"));
        final OffsetDateTime hour = rs.getObject("hour_cursor", OffsetDateTime.class);
        final LocalDate day = rs.getObject("day_cursor", LocalDate.class);
        final OffsetDateTime completedAt = rs.getObject("completed_at", OffsetDateTime.class);
        final boolean accumulating = phase == Phase.ACCUMULATE;
        final FoldCursor fold = phase == Phase.FOLD && hour != null
            ? new FoldCursor(rs.getString("vehicle_cursor"), hour.toInstant(), rs.getInt("stop_cursor")) : null;
        final ReduceCursor reduce = phase == Phase.REDUCE && day != null
            ? new ReduceCursor(rs.getInt("stop_cursor"), timeSlotOf(rs.getString("slot_cursor")), day) : null;
        return new DemandStatisticsRun(routeVersionId, rs.getObject("run_id", UUID.class),
            rs.getLong("quality_revision"), phase, rs.getObject("data_until", OffsetDateTime.class).toInstant(),
            rs.getLong("input_until_id"), accumulating ? rs.getString("vehicle_cursor") : NO_VEHICLE,
            accumulating ? rs.getLong("cursor_id") : 0, fold, reduce,
            completedAt == null ? null : completedAt.toInstant());
    }

    private static String storedTimeSlotOf(final TimeSlot timeSlot) {
        return timeSlot.name().toLowerCase(Locale.ROOT);
    }

    private static TimeSlot timeSlotOf(final String stored) {
        return TimeSlot.valueOf(stored.toUpperCase(Locale.ROOT));
    }

    private static OffsetDateTime offsetOf(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
