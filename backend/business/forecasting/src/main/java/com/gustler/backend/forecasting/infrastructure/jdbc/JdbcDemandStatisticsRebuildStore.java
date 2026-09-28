package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.statistics.DemandStatisticsRebuildStore;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild.ScanWindow;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDemandStatisticsRebuildStore implements DemandStatisticsRebuildStore {

    private static final String GROUP_END = """
        SELECT id,response_received_at FROM (
            SELECT id,response_received_at FROM observation_batch
            WHERE route_version_id=:version AND id<=:upper AND response_received_at IS NOT NULL
              AND (response_received_at,id)>(COALESCE(CAST(:afterAt AS timestamptz),'-infinity'::timestamptz),:afterId)
            ORDER BY response_received_at,id LIMIT :groupSize
        ) batches
        ORDER BY response_received_at DESC,id DESC LIMIT 1
        """;

    private static final String GROUP_OBSERVATIONS = """
        WITH batches AS MATERIALIZED (
            SELECT id FROM observation_batch
            WHERE route_version_id=:version AND id<=:batchUpper AND response_received_at IS NOT NULL
              AND (response_received_at,id)>(COALESCE(CAST(:afterAt AS timestamptz),'-infinity'::timestamptz),:afterId)
              AND (response_received_at,id)<=(:endAt,:endId)
            ORDER BY response_received_at,id LIMIT :groupSize
        )
        SELECT observation.id FROM batches b JOIN LATERAL (
            SELECT id FROM vehicle_observation WHERE observation_batch_id=b.id
              AND (:vehicle='' OR vehicle_id=:vehicle) AND id>:cursor AND id<=:upper
            ORDER BY id LIMIT :limit
        ) observation ON true ORDER BY observation.id LIMIT :limit
        """;

    private static final String AGGREGATE = """
        WITH page AS MATERIALIZED (
            SELECT id FROM vehicle_observation WHERE id IN (:ids)
        ), added AS (
            INSERT INTO stop_demand_rebuild_total AS total(route_version_id, scope_vehicle_id, request_id,
                vehicle_id, arrived_hour_start, target_stop_order, sample_count, arrival_seats_sum, net_boarding_sum)
            SELECT :version, :vehicle, :request, source.vehicle_id,
                date_trunc('hour', evaluation.arrived_at, 'UTC'), forecast.target_stop_order,
                count(*), sum(evaluation.seats_on_arrival), sum(source.remaining_seats-evaluation.seats_on_arrival)
            FROM page JOIN forecast_eligible_observation source ON source.id=page.id
            JOIN seat_forecast forecast ON forecast.vehicle_observation_id=source.id
            JOIN forecast_evaluation evaluation ON evaluation.vehicle_observation_id=forecast.vehicle_observation_id
              AND evaluation.target_stop_order=forecast.target_stop_order
            JOIN forecast_eligible_observation arrival ON arrival.id=evaluation.arrival_observation_id
              AND %s
            JOIN route_stop stop ON stop.route_version_id=forecast.route_version_id AND stop.stop_order=forecast.target_stop_order
            WHERE source.route_version_id=:version AND (:vehicle='' OR source.vehicle_id=:vehicle)
              AND source.vehicle_id IS NOT NULL AND source.remaining_seats IS NOT NULL
              AND evaluation.scoring_state='SETTLED' AND forecast.stops_to_target=1 AND stop.boarding_allowed
              AND evaluation.scored_at<=:until
              AND NOT EXISTS (SELECT 1 FROM stop_demand_pending_sample pending
                  WHERE pending.prediction_observation_id=source.id AND pending.target_stop_order=forecast.target_stop_order
                    AND pending.id>:inputUntil)
            GROUP BY source.vehicle_id, date_trunc('hour', evaluation.arrived_at, 'UTC'), forecast.target_stop_order
            ON CONFLICT(request_id,vehicle_id,arrived_hour_start,target_stop_order) DO UPDATE SET
                sample_count=total.sample_count+EXCLUDED.sample_count,
                arrival_seats_sum=total.arrival_seats_sum+EXCLUDED.arrival_seats_sum,
                net_boarding_sum=total.net_boarding_sum+EXCLUDED.net_boarding_sum
            RETURNING 1
        ) SELECT count(*) FROM added
        """.formatted(EligibleObservationSql.ARRIVAL_MATCHES_SOURCE);

    private static final String COPY = """
        WITH page AS MATERIALIZED (
            SELECT * FROM stop_demand_rebuild_total WHERE request_id=:request AND id>:cursor ORDER BY id LIMIT :limit
        ), copied AS (
            INSERT INTO stop_demand_current_total(route_version_id,vehicle_id,arrived_hour_start,target_stop_order,
                sample_count,arrival_seats_sum,net_boarding_sum)
            SELECT route_version_id,vehicle_id,arrived_hour_start,target_stop_order,sample_count,arrival_seats_sum,net_boarding_sum FROM page
            ON CONFLICT(route_version_id,vehicle_id,arrived_hour_start,target_stop_order) DO UPDATE SET
                sample_count=EXCLUDED.sample_count,arrival_seats_sum=EXCLUDED.arrival_seats_sum,net_boarding_sum=EXCLUDED.net_boarding_sum
            RETURNING route_version_id,vehicle_id
        ), registered AS (
            INSERT INTO stop_demand_vehicle SELECT DISTINCT route_version_id,vehicle_id FROM copied
            ON CONFLICT DO NOTHING RETURNING 1
        ) SELECT count(*) AS size, COALESCE(max(id),:cursor) AS cursor FROM page
        """;

    private static final String ACKNOWLEDGE = """
        WITH page AS MATERIALIZED (
            SELECT id,scored_at FROM stop_demand_pending_sample
            WHERE route_version_id=:version AND (:vehicle='' OR vehicle_id=:vehicle)
              AND id>:cursor AND id<=:upper ORDER BY id LIMIT :limit
        ), removed AS (
            DELETE FROM stop_demand_pending_sample pending USING page
            WHERE pending.id=page.id AND page.scored_at<=:until RETURNING pending.id
        ) SELECT count(*) AS size, COALESCE(max(id),:cursor) AS cursor FROM page
        """;

    private final JdbcClient jdbc;

    public JdbcDemandStatisticsRebuildStore(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int clearLeftoverTotals(final long routeVersionId, final RebuildScope scope, final int limit) {
        return jdbc.sql("""
            DELETE FROM stop_demand_rebuild_total WHERE id IN (
                SELECT id FROM stop_demand_rebuild_total WHERE route_version_id=? AND scope_vehicle_id=? LIMIT ?)
            """).params(routeVersionId, scope.vehicleId(), limit).update();
    }

    @Override
    public long lastObservationId() {
        return jdbc.sql("SELECT COALESCE(max(id),0) FROM vehicle_observation").query(Long.class).single();
    }

    @Override
    public long lastBatchId() {
        return jdbc.sql("SELECT COALESCE(max(id),0) FROM observation_batch").query(Long.class).single();
    }

    @Override
    public List<Long> observationPage(final long afterObservationId, final long observationUntilId, final int limit) {
        return jdbc.sql("SELECT id FROM vehicle_observation WHERE id>? AND id<=? ORDER BY id LIMIT ?")
            .params(afterObservationId, observationUntilId, limit).query(Long.class).list();
    }

    @Override
    public Optional<BatchPosition> nextGroupEnd(final long routeVersionId, final ScanWindow window,
        final int groupSize) {
        return jdbc.sql(GROUP_END).param("version", routeVersionId).param("upper", window.batchUntilId())
            .param("afterAt", window.after().map(JdbcDemandStatisticsRebuildStore::offsetOf).orElse(null))
            .param("afterId", window.afterBatchId()).param("groupSize", groupSize)
            .query((rs, row) -> new BatchPosition(rs.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                rs.getLong("id")))
            .optional();
    }

    @Override
    public List<Long> groupObservationPage(final long routeVersionId, final ScanWindow window,
        final RebuildScope scope, final long afterObservationId, final long observationUntilId, final int groupSize,
        final int limit) {
        return jdbc.sql(GROUP_OBSERVATIONS).param("version", routeVersionId).param("batchUpper", window.batchUntilId())
            .param("afterAt", window.after().map(JdbcDemandStatisticsRebuildStore::offsetOf).orElse(null))
            .param("afterId", window.afterBatchId())
            .param("endAt", offsetOf(window.groupEndAt())).param("endId", window.groupEndId())
            .param("groupSize", groupSize).param("vehicle", scope.vehicleId()).param("cursor", afterObservationId)
            .param("upper", observationUntilId).param("limit", limit)
            .query(Long.class).list();
    }

    @Override
    public void addRebuildTotals(final DemandStatisticsRebuild rebuild, final List<Long> observationIds) {
        if (observationIds.isEmpty()) {
            return;
        }
        jdbc.sql(AGGREGATE).param("version", rebuild.routeVersionId()).param("vehicle", rebuild.scope().vehicleId())
            .param("request", rebuild.requestId()).param("ids", observationIds)
            .param("until", offsetOf(rebuild.dataUntil())).param("inputUntil", rebuild.inputUntilId())
            .query(Long.class).single();
    }

    @Override
    public int clearCurrentTotals(final long routeVersionId, final RebuildScope scope, final int limit) {
        return jdbc.sql("""
            DELETE FROM stop_demand_current_total WHERE (route_version_id,vehicle_id,arrived_hour_start,target_stop_order) IN (
                SELECT route_version_id,vehicle_id,arrived_hour_start,target_stop_order FROM stop_demand_current_total
                WHERE route_version_id=? AND (?='' OR vehicle_id=?) LIMIT ?)
            """).params(routeVersionId, scope.vehicleId(), scope.vehicleId(), limit).update();
    }

    @Override
    public Page copyToCurrentTotals(final UUID requestId, final long afterTotalId, final int limit) {
        return jdbc.sql(COPY).param("request", requestId).param("cursor", afterTotalId).param("limit", limit)
            .query((rs, row) -> new Page(rs.getInt("size"), rs.getLong("cursor"))).single();
    }

    @Override
    public Page acknowledgeSamples(final long routeVersionId, final RebuildScope scope, final long afterSampleId,
        final long inputUntilId, final Instant dataUntil, final int limit) {
        return jdbc.sql(ACKNOWLEDGE).param("version", routeVersionId).param("vehicle", scope.vehicleId())
            .param("cursor", afterSampleId).param("upper", inputUntilId).param("until", offsetOf(dataUntil))
            .param("limit", limit)
            .query((rs, row) -> new Page(rs.getInt("size"), rs.getLong("cursor"))).single();
    }

    @Override
    public int cleanRebuildTotals(final UUID requestId, final int limit) {
        return jdbc.sql("""
            DELETE FROM stop_demand_rebuild_total WHERE id IN (SELECT id FROM stop_demand_rebuild_total WHERE request_id=? LIMIT ?)
            """).params(requestId, limit).update();
    }

    private static OffsetDateTime offsetOf(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
