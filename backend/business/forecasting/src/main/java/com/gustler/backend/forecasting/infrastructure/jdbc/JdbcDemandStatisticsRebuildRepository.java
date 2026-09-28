package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuildRepository;
import com.gustler.backend.forecasting.domain.statistics.RebuildPhase;
import com.gustler.backend.forecasting.domain.statistics.RebuildScanWindow;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDemandStatisticsRebuildRepository implements DemandStatisticsRebuildRepository {

    private static final String SELECT = """
        SELECT progress.request_id, progress.quality_revision, progress.data_until, progress.input_until_id,
               progress.observation_until_id, progress.cursor_id, progress.phase,
               scan.route_version_id IS NOT NULL AS has_scan, scan.batch_until_id, scan.after_at,
               scan.after_batch_id, scan.group_end_at, scan.group_end_id
        FROM stop_demand_rebuild_progress progress
        LEFT JOIN stop_demand_rebuild_scan scan
          ON scan.route_version_id = progress.route_version_id AND scan.vehicle_id = progress.vehicle_id
        WHERE progress.route_version_id = ? AND progress.vehicle_id = ?
        """;

    private static final String UPSERT_PROGRESS = """
        INSERT INTO stop_demand_rebuild_progress(route_version_id, vehicle_id, request_id, quality_revision,
            data_until, input_until_id, observation_until_id, cursor_id, phase)
        VALUES (:version, :vehicle, :requestId, :qualityRevision, :dataUntil, :inputUntilId, :observationUntilId,
            :cursorId, :phase)
        ON CONFLICT (route_version_id, vehicle_id) DO UPDATE SET request_id = EXCLUDED.request_id,
            quality_revision = EXCLUDED.quality_revision, data_until = EXCLUDED.data_until,
            input_until_id = EXCLUDED.input_until_id, observation_until_id = EXCLUDED.observation_until_id,
            cursor_id = EXCLUDED.cursor_id, phase = EXCLUDED.phase
        """;

    private static final String UPSERT_SCAN = """
        INSERT INTO stop_demand_rebuild_scan(route_version_id, vehicle_id, batch_until_id, after_at, after_batch_id,
            group_end_at, group_end_id)
        VALUES (:version, :vehicle, :batchUntilId, :afterAt, :afterBatchId, :groupEndAt, :groupEndId)
        ON CONFLICT (route_version_id, vehicle_id) DO UPDATE SET batch_until_id = EXCLUDED.batch_until_id,
            after_at = EXCLUDED.after_at, after_batch_id = EXCLUDED.after_batch_id,
            group_end_at = EXCLUDED.group_end_at, group_end_id = EXCLUDED.group_end_id
        """;

    private final JdbcClient jdbc;

    public JdbcDemandStatisticsRebuildRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DemandStatisticsRebuild> find(final long routeVersionId, final RebuildScope scope) {
        return jdbc.sql(SELECT).params(routeVersionId, scope.vehicleId())
            .query((rs, row) -> rebuildOf(routeVersionId, scope, rs)).optional();
    }

    @Override
    public void save(final DemandStatisticsRebuild rebuild) {
        jdbc.sql(UPSERT_PROGRESS)
            .param("version", rebuild.routeVersionId())
            .param("vehicle", rebuild.scope().vehicleId())
            .param("requestId", rebuild.requestId())
            .param("qualityRevision", rebuild.qualityRevision())
            .param("dataUntil", offsetOf(rebuild.dataUntil()))
            .param("inputUntilId", rebuild.inputUntilId())
            .param("observationUntilId", rebuild.observationUntilId())
            .param("cursorId", rebuild.cursorId())
            .param("phase", rebuild.phase().name())
            .update();
        rebuild.scan().ifPresent(scan -> jdbc.sql(UPSERT_SCAN)
            .param("version", rebuild.routeVersionId())
            .param("vehicle", rebuild.scope().vehicleId())
            .param("batchUntilId", scan.batchUntilId())
            .param("afterAt", scan.afterAt() == null ? null : offsetOf(scan.afterAt()))
            .param("afterBatchId", scan.afterBatchId())
            .param("groupEndAt", scan.groupEndAt() == null ? null : offsetOf(scan.groupEndAt()))
            .param("groupEndId", scan.groupEndId())
            .update());
    }

    @Override
    public void delete(final long routeVersionId, final RebuildScope scope) {
        jdbc.sql("DELETE FROM stop_demand_rebuild_progress WHERE route_version_id = ? AND vehicle_id = ?")
            .params(routeVersionId, scope.vehicleId()).update();
    }

    private static DemandStatisticsRebuild rebuildOf(final long routeVersionId, final RebuildScope scope,
        final ResultSet rs) throws SQLException {
        final RebuildScanWindow scan = rs.getBoolean("has_scan") ? new RebuildScanWindow(rs.getLong("batch_until_id"),
            instantOrNull(rs.getObject("after_at", OffsetDateTime.class)), rs.getLong("after_batch_id"),
            instantOrNull(rs.getObject("group_end_at", OffsetDateTime.class)), rs.getLong("group_end_id")) : null;
        return new DemandStatisticsRebuild(routeVersionId, scope, rs.getObject("request_id", UUID.class),
            rs.getLong("quality_revision"), rs.getObject("data_until", OffsetDateTime.class).toInstant(),
            rs.getLong("input_until_id"), rs.getLong("observation_until_id"), rs.getLong("cursor_id"),
            RebuildPhase.valueOf(rs.getString("phase")), scan);
    }

    private static Instant instantOrNull(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offsetOf(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
