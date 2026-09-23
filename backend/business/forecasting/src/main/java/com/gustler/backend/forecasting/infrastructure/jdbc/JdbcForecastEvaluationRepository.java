package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationRoute;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** 평가 상태와 당시 도착 관측의 근거 값을 예측과 별도로 저장한다. */
@Repository
public class JdbcForecastEvaluationRepository implements ForecastEvaluationRepository {

    private static final String SELECT_PENDING = """
        SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
               source.vehicle_id, forecast.stops_to_target, batch.response_received_at,
               forecast.generated_at, source.quality_direction
        FROM quality_eligible_seat_forecast forecast
        JOIN forecast_eligible_observation source ON source.id = forecast.vehicle_observation_id
        JOIN observation_batch batch ON batch.id = source.observation_batch_id
        WHERE forecast.scoring_state = 'PENDING' AND forecast.route_version_id = :routeVersionId
        ORDER BY forecast.generated_at, forecast.vehicle_observation_id, forecast.target_stop_order
        LIMIT :limit
        """;

    private static final String CAN_COMPLETE = """
        SELECT EXISTS (
            SELECT 1 FROM forecast_evaluation evaluation
            JOIN forecast_eligible_observation source ON source.id = evaluation.vehicle_observation_id
            WHERE evaluation.vehicle_observation_id = :vehicleObservationId
              AND evaluation.target_stop_order = :targetStopOrder
              AND evaluation.scoring_state = 'PENDING'
              AND (CAST(:arrivalObservationId AS bigint) IS NULL OR EXISTS (
                  SELECT 1 FROM forecast_eligible_observation arrival
                  WHERE arrival.id = :arrivalObservationId
                    AND arrival.quality_direction = source.quality_direction
                    AND arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
                    AND arrival.route_version_id = source.route_version_id)))
        """;

    /** 근거의 정류장 순번은 원 관측의 stop_order다. 평가 판정에 사용하는 passed_stop_order와 구분한다. */
    private static final String COMPLETE = """
        UPDATE forecast_evaluation evaluation
        SET scoring_state = :scoringState,
            arrival_observation_id = :arrivalObservationId,
            seats_on_arrival = :seatsOnArrival,
            scored_at = :scoredAt,
            arrived_at = arrival_batch.response_received_at,
            arrival_route_version_id = arrival.route_version_id,
            arrival_vehicle_id = arrival.vehicle_id,
            arrival_stop_order = arrival.stop_order,
            arrival_running_state = arrival.running_state,
            arrival_remaining_seats = arrival.remaining_seats,
            arrival_seat_unknown_reason = arrival.seat_unknown_reason,
            arrival_vehicle_trip_key = arrival.vehicle_trip_key,
            arrival_quality_direction = arrival.quality_direction
        FROM seat_forecast forecast
        JOIN route_version version ON version.id = forecast.route_version_id
        JOIN route_data_quality quality ON quality.route_id = version.route_id
        JOIN forecast_eligible_observation source ON source.id = forecast.vehicle_observation_id
        LEFT JOIN forecast_eligible_observation arrival ON arrival.id = :arrivalObservationId
        LEFT JOIN observation_batch arrival_batch ON arrival_batch.id = arrival.observation_batch_id
        WHERE evaluation.vehicle_observation_id = :vehicleObservationId
          AND evaluation.target_stop_order = :targetStopOrder
          AND evaluation.scoring_state = 'PENDING'
          AND forecast.vehicle_observation_id = evaluation.vehicle_observation_id
          AND forecast.target_stop_order = evaluation.target_stop_order
          AND (CAST(:arrivalObservationId AS bigint) IS NULL OR (
              arrival.id IS NOT NULL AND arrival.quality_direction = source.quality_direction
              AND arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
              AND arrival.route_version_id = source.route_version_id))
        RETURNING version.route_id, forecast.stops_to_target, forecast.seat_full_chance_raw,
                  evaluation.arrived_at, evaluation.seats_on_arrival, evaluation.scoring_state,
                  forecast.quality_revision = quality.quality_revision AS usable_for_calibration
        """;

    private final JdbcClient jdbcClient;

    public JdbcForecastEvaluationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<EvaluationRoute> findRoutesWithPendingForecasts() {
        return jdbcClient.sql("""
            SELECT DISTINCT version.id, version.route_id
            FROM quality_eligible_seat_forecast forecast
            JOIN route_version version ON version.id = forecast.route_version_id
            WHERE forecast.scoring_state = 'PENDING'
            ORDER BY version.route_id, version.id
            """).query((row, index) -> new EvaluationRoute(row.getLong("route_id"), row.getLong("id"))).list();
    }

    @Override
    public List<Long> findRouteIdsForObservations(List<Long> observationIds) {
        if (observationIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
            SELECT DISTINCT version.route_id FROM vehicle_observation source
            JOIN route_version version ON version.id = source.route_version_id
            WHERE source.id IN (:sourceIds) ORDER BY version.route_id
            """).param("sourceIds", observationIds).query(Long.class).list();
    }

    @Override
    public boolean canComplete(ForecastEvaluation evaluation) {
        return parameters(CAN_COMPLETE, evaluation).query(Boolean.class).single();
    }

    @Override
    public List<PendingForecast> findPending(final long routeVersionId, final int limit) {
        return jdbcClient.sql(SELECT_PENDING)
            .param("routeVersionId", routeVersionId).param("limit", limit)
            .query((row, index) -> new PendingForecast(
                row.getLong("vehicle_observation_id"), row.getInt("target_stop_order"),
                row.getLong("route_version_id"), row.getString("vehicle_id"), row.getInt("stops_to_target"),
                row.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                row.getObject("generated_at", OffsetDateTime.class).toInstant(),
                row.getObject("quality_direction", Long.class)))
            .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SettledForecast> settle(List<ForecastEvaluation> evaluations) {
        if (evaluations.isEmpty()) {
            return List.of();
        }
        List<SettledForecast> newlySettled = new ArrayList<>();
        for (ForecastEvaluation evaluation : evaluations) {
            if (evaluation.state() == ScoringState.PENDING) {
                throw new IllegalArgumentException("완료된 평가만 저장할 수 있다");
            }
            newlySettled.addAll(parameters(COMPLETE, evaluation)
                .param("scoringState", evaluation.state().name())
                .param("seatsOnArrival", evaluation.result().seatsOnArrival())
                .param("scoredAt", offsetOf(evaluation.scoredAt()))
                .query((row, index) -> {
                    if (!ScoringState.SETTLED.name().equals(row.getString("scoring_state"))
                        || !row.getBoolean("usable_for_calibration")) {
                        return null;
                    }
                    return new SettledForecast(row.getLong("route_id"), row.getInt("stops_to_target"),
                        row.getDouble("seat_full_chance_raw"), row.getObject("arrived_at", OffsetDateTime.class).toInstant(),
                        row.getInt("seats_on_arrival"));
                }).list().stream().filter(java.util.Objects::nonNull).toList());
        }
        return List.copyOf(newlySettled);
    }

    private JdbcClient.StatementSpec parameters(String sql, ForecastEvaluation evaluation) {
        return jdbcClient.sql(sql).param("vehicleObservationId", evaluation.vehicleObservationId())
            .param("targetStopOrder", evaluation.targetStopOrder())
            .param("arrivalObservationId", evaluation.result().arrivalObservationId());
    }

    private static OffsetDateTime offsetOf(Instant timestamp) {
        return timestamp.atOffset(ZoneOffset.UTC);
    }

}
