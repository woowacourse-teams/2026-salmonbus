package com.gustler.backend.forecasting.infrastructure.alighting;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureUnavailableException;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.infrastructure.ReadWorkBudget;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 과거 예보를 발행하지 않고, 한정된 배치씩 DB를 읽어 로컬 체크포인트만 복구한다. */
public final class AlightingAwareHistoryBootstrap {
    private final JdbcClient jdbc;

    public AlightingAwareHistoryBootstrap(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int rebuild(
        RouteStops stops,
        AlightingAwareRouteStatistics statistics,
        final long verifiedOriginBatchId,
        final long throughBatchId,
        final int batchStep,
        final int maximumRows,
        String checkpointDirectory
    ) {
        Progress progress = rebuild(stops, statistics, verifiedOriginBatchId, throughBatchId, batchStep, maximumRows,
            checkpointDirectory, 10, 30);
        if (!progress.complete()) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_BOOTSTRAP_READ_BUDGET_EXHAUSTED");
        }
        return progress.checkpoints();
    }

    public Progress rebuild(
        RouteStops stops, AlightingAwareRouteStatistics statistics, long verifiedOriginBatchId,
        long throughBatchId, int batchStep, int maximumRows, String checkpointDirectory,
        int maximumCheckpoints, int maximumSeconds
    ) {
        validateRequest(checkpointDirectory, batchStep);
        ReadWorkBudget budget = new ReadWorkBudget(maximumCheckpoints, Duration.ofSeconds(maximumSeconds));
        if (statistics.confirmedProfile()) {
            requireQualityCutover();
            requireCursorIndex();
        }
        Instant through = through(stops.routeVersionId(), throughBatchId, statistics.confirmedProfile());
        AlightingAwareHistorySeedReader reader = statistics.confirmedProfile()
            ? AlightingAwareHistorySeedReader.confirmed(checkpointDirectory, maximumRows)
            : new AlightingAwareHistorySeedReader(checkpointDirectory, maximumRows);
        AlightingAwareHistorySeedReader.Seed seed = reader.read(stops.routeVersionId());
        Cursor after = initialCursor(seed);
        int checkpoints = 0;
        JdbcAlightingAwareFeatureQuery query = new JdbcAlightingAwareFeatureQuery(jdbc, new VehicleCapacityCache(
            new JdbcVehicleCapacityStore(jdbc)), stops.routeVersionId() + ":" + verifiedOriginBatchId,
            maximumRows, false, checkpointDirectory);
        while (after.time().isBefore(through) || (after.time().equals(through) && after.id() < throughBatchId)) {
            if (!budget.tryAcquire()) {
                return new Progress(checkpoints, after.id(), false);
            }
            List<Cursor> steps = steps(
                statistics.route(), after, through, throughBatchId, batchStep, statistics.confirmedProfile());
            if (steps.isEmpty()) {
                throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_BOOTSTRAP_TARGET_UNREACHABLE");
            }
            Cursor next = steps.getLast();
            WorkerOperationLog.run("alighting_history_bootstrap_checkpoint", stops.routeVersionId(),
                () -> query.readForCheckpoint(next.id(), stops, statistics));
            checkpoints++;
            after = next;
        }
        return new Progress(checkpoints, after.id(), true);
    }

    public record Progress(int checkpoints, long processedThroughBatchId, boolean complete) {
    }

    private static void validateRequest(String checkpointDirectory, final int batchStep) {
        if (checkpointDirectory.isBlank() || isInvalidBatchStep(batchStep)) {
            throw new IllegalArgumentException("Checkpoint directory and batch step 1..1000 required");
        }
    }

    private static boolean isInvalidBatchStep(final int batchStep) {
        return batchStep < 1 || batchStep > 1000;
    }

    private void requireQualityCutover() {
        // V66은 제외 관측의 좌석을 비우고 route.data_revision을 추가한다.
        boolean migrated = jdbc.sql("""
            SELECT EXISTS (
                SELECT 1 FROM pg_attribute
                WHERE attrelid='route'::regclass AND attname='data_revision' AND NOT attisdropped
            )
            """).query(Boolean.class).single();
        if (!migrated) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_HISTORY_REQUIRES_QUALITY_CUTOVER");
        }
    }

    private void requireCursorIndex() {
        boolean indexed = jdbc.sql("""
            SELECT coalesce((SELECT indisvalid AND indisready FROM pg_index
                WHERE indexrelid=to_regclass('ix_batch_confirmed_history')),false)
            """).query(Boolean.class).single();
        if (!indexed) {
            throw new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_HISTORY_REQUIRES_CURSOR_INDEX");
        }
    }

    private Instant through(final long routeVersionId, final long batchId, final boolean confirmed) {
        return jdbc.sql(JdbcAlightingAwareFeatureQuery.profileSql("SELECT response_received_at FROM observation_batch WHERE id=:id AND route_version_id=:version", confirmed))
            .param("id", batchId).param("version", routeVersionId).query(OffsetDateTime.class).optional()
            .orElseThrow(() -> new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_SOURCE_BATCH_MISSING")).toInstant();
    }

    private static Cursor initialCursor(AlightingAwareHistorySeedReader.Seed seed) {
        if (seed == null) {
            return new Cursor(0, 0, Instant.EPOCH);
        }
        return new Cursor(seed.batchId(), 0, seed.through());
    }

    private List<Cursor> steps(
        String route,
        Cursor after,
        Instant through,
        final long throughBatchId,
        final int batchStep,
        final boolean confirmed
    ) {
        String candidates = JdbcAlightingAwareFeatureQuery.profileSql("""
            SELECT batch.id,batch.route_version_id,batch.response_received_at AS available_at
            FROM observation_batch batch
            WHERE batch.route_version_id=version.id AND batch.response_received_at IS NOT NULL
              AND (batch.response_received_at,batch.id)>(:after,:afterId)
              AND (batch.response_received_at,batch.id)<=(:through,:throughId)
            ORDER BY batch.response_received_at,batch.id LIMIT :step
            """, confirmed);
        return jdbc.sql("""
            SELECT batch.id,batch.route_version_id,batch.available_at
            FROM route_version version JOIN route ON route.id=version.route_id
            CROSS JOIN LATERAL (%s) batch
            WHERE route.display_name=:name
            ORDER BY batch.available_at,batch.id LIMIT :step
            """.formatted(candidates)).param("name", route).param("after", after.time().atOffset(ZoneOffset.UTC))
            .param("afterId", after.id()).param("through", through.atOffset(ZoneOffset.UTC))
            .param("throughId", throughBatchId).param("step", batchStep)
            .query((row, index) -> new Cursor(row.getLong(1), row.getLong(2),
                row.getObject(3, OffsetDateTime.class).toInstant()))
            .list();
    }

    private record Cursor(long id, long version, Instant time) {
    }
}
