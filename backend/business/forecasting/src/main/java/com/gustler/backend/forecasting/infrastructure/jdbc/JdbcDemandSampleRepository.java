package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.statistics.DemandSample;
import com.gustler.backend.forecasting.domain.statistics.DemandSamplePage;
import com.gustler.backend.forecasting.domain.statistics.DemandSampleRepository;
import com.gustler.backend.forecasting.domain.statistics.DemandSamplePage.PendingSample;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDemandSampleRepository implements DemandSampleRepository {

    private static final String INSERT = """
        INSERT INTO stop_demand_pending_sample (
            route_version_id, prediction_observation_id, arrival_observation_id,
            vehicle_id, target_stop_order, arrived_at, scored_at,
            prediction_remaining_seats, arrival_remaining_seats
        ) VALUES (
            :routeVersionId, :predictionObservationId, :arrivalObservationId,
            :vehicleId, :targetStopOrder, :arrivedAt, :scoredAt,
            :predictionRemainingSeats, :arrivalRemainingSeats
        )
        """;

    private static final String LOCK_PAGE = """
        WITH page AS MATERIALIZED (
            SELECT * FROM stop_demand_pending_sample
            WHERE route_version_id = :version AND vehicle_id = :vehicle AND id > :afterInputId AND id <= :inputUntilId
            ORDER BY id LIMIT :limit FOR UPDATE
        )
        SELECT page.*, EXISTS (
            SELECT 1 FROM forecast_eligible_observation source
            JOIN forecast_eligible_observation arrival ON arrival.id = page.arrival_observation_id
              AND arrival.route_version_id = source.route_version_id
              AND arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
              AND arrival.quality_direction = source.quality_direction
            WHERE source.id = page.prediction_observation_id
        ) AS usable
        FROM page
        ORDER BY page.id
        """;

    private final JdbcClient jdbc;

    public JdbcDemandSampleRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(final List<DemandSample> samples) {
        for (final DemandSample sample : samples) {
            jdbc.sql(INSERT)
                .param("routeVersionId", sample.routeVersionId())
                .param("predictionObservationId", sample.predictionObservationId())
                .param("arrivalObservationId", sample.arrivalObservationId())
                .param("vehicleId", sample.vehicleId())
                .param("targetStopOrder", sample.targetStopOrder())
                .param("arrivedAt", offsetOf(sample.arrivedAt()))
                .param("scoredAt", offsetOf(sample.scoredAt()))
                .param("predictionRemainingSeats", sample.predictionRemainingSeats())
                .param("arrivalRemainingSeats", sample.arrivalRemainingSeats())
                .update();
        }
    }

    @Override
    public DemandSamplePage lockPage(final long routeVersionId, final String vehicleId, final long afterInputId,
        final long inputUntilId, final int limit) {
        return new DemandSamplePage(jdbc.sql(LOCK_PAGE)
            .param("version", routeVersionId).param("vehicle", vehicleId)
            .param("afterInputId", afterInputId).param("inputUntilId", inputUntilId).param("limit", limit)
            .query((rs, row) -> new PendingSample(rs.getLong("id"), new DemandSample(
                rs.getLong("route_version_id"), rs.getLong("prediction_observation_id"),
                rs.getLong("arrival_observation_id"), rs.getString("vehicle_id"), rs.getInt("target_stop_order"),
                rs.getObject("arrived_at", OffsetDateTime.class).toInstant(),
                rs.getObject("scored_at", OffsetDateTime.class).toInstant(),
                rs.getInt("prediction_remaining_seats"), rs.getInt("arrival_remaining_seats")),
                rs.getBoolean("usable")))
            .list());
    }

    @Override
    public void remove(final List<Long> sampleIds) {
        if (sampleIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM stop_demand_pending_sample WHERE id IN (:ids)").param("ids", sampleIds).update();
    }

    @Override
    public long lastSampleId() {
        return jdbc.sql("SELECT COALESCE(max(id), 0) FROM stop_demand_pending_sample").query(Long.class).single();
    }

    private static OffsetDateTime offsetOf(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
