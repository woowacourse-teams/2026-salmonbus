package com.gustler.backend.forecasting.infrastructure.les;

import com.gustler.backend.forecasting.application.les.LesFeatureUnavailableException;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 과거 예보를 발행하지 않고, 한정된 배치씩 DB를 읽어 로컬 체크포인트만 복구한다. */
public final class LesHistoryBootstrap {
    private final JdbcClient jdbc;

    public LesHistoryBootstrap(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int rebuild(
        RouteStops stops,
        LesRouteStatistics statistics,
        final long verifiedOriginBatchId,
        final long throughBatchId,
        final int batchStep,
        final int maximumRows,
        String checkpointDirectory
    ) {
        validateRequest(checkpointDirectory, batchStep);
        Instant through = through(stops.routeVersionId(), throughBatchId, statistics.confirmedProfile());
        String seedDirectory = checkpointDirectory;
        if (statistics.confirmedProfile()) {
            seedDirectory = Path.of(checkpointDirectory).resolve(LesRouteStatistics.CONFIRMED_PROFILE).toString();
        }
        LesHistorySeedReader.Seed seed = new LesHistorySeedReader(seedDirectory, maximumRows)
            .read(stops.routeVersionId());
        Cursor after = initialCursor(seed);
        int checkpoints = 0;
        JdbcLesFeatureQuery query = new JdbcLesFeatureQuery(jdbc, stops.routeVersionId() + ":" + verifiedOriginBatchId,
            maximumRows, false, checkpointDirectory);
        while (true) {
            List<Cursor> steps = steps(
                statistics.route(), after, through, throughBatchId, batchStep, statistics.confirmedProfile());
            if (steps.isEmpty()) {
                return checkpoints;
            }
            Cursor next = steps.getLast();
            query.readForCheckpoint(next.id(), stops, statistics);
            checkpoints++;
            after = next;
        }
    }

    private static void validateRequest(String checkpointDirectory, final int batchStep) {
        if (checkpointDirectory.isBlank() || isInvalidBatchStep(batchStep)) {
            throw new IllegalArgumentException("Checkpoint directory and batch step 1..1000 required");
        }
    }

    private static boolean isInvalidBatchStep(final int batchStep) {
        return batchStep < 1 || batchStep > 1000;
    }

    private Instant through(final long routeVersionId, final long batchId, final boolean confirmed) {
        return jdbc.sql(JdbcLesFeatureQuery.profileSql("SELECT response_received_at FROM observation_batch WHERE id=:id AND route_version_id=:version", confirmed))
            .param("id", batchId).param("version", routeVersionId).query(OffsetDateTime.class).optional()
            .orElseThrow(() -> new LesFeatureUnavailableException("LES_SOURCE_BATCH_MISSING")).toInstant();
    }

    private static Cursor initialCursor(LesHistorySeedReader.Seed seed) {
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
        return jdbc.sql(JdbcLesFeatureQuery.profileSql("""
            SELECT batch.id,batch.route_version_id,batch.response_received_at
            FROM observation_batch batch JOIN route_version version ON version.id=batch.route_version_id
            JOIN route route ON route.id=version.route_id
            WHERE route.display_name=:name AND batch.response_received_at IS NOT NULL
              AND (batch.response_received_at,batch.id)>(:after,:afterId)
              AND (batch.response_received_at,batch.id)<=(:through,:throughId)
            ORDER BY batch.response_received_at,batch.id LIMIT :step
            """, confirmed)).param("name", route).param("after", after.time().atOffset(ZoneOffset.UTC))
            .param("afterId", after.id()).param("through", through.atOffset(ZoneOffset.UTC))
            .param("throughId", throughBatchId).param("step", batchStep)
            .query((row, index) -> new Cursor(row.getLong(1), row.getLong(2),
                row.getObject(3, OffsetDateTime.class).toInstant()))
            .list();
    }

    private record Cursor(long id, long version, Instant time) {
    }
}
