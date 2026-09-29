package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.evaluation.SettledEvaluation;
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

    private static final String INSERT_PENDING = """
        INSERT INTO forecast_evaluation (vehicle_observation_id, target_stop_order, route_version_id)
        VALUES (?, ?, ?)
        ON CONFLICT (vehicle_observation_id, target_stop_order) DO NOTHING
        """;

    private static final String SELECT_PENDING_KEYS = """
        SELECT vehicle_observation_id, target_stop_order
        FROM forecast_evaluation
        WHERE scoring_state = 'PENDING' AND route_version_id = :routeVersionId
          AND (vehicle_observation_id, target_stop_order) > (:afterObservationId, :afterStopOrder)
        ORDER BY vehicle_observation_id, target_stop_order
        LIMIT :limit
        """;

    private static final String SELECT_PENDING = """
        SELECT forecast.vehicle_observation_id, forecast.target_stop_order, forecast.route_version_id,
               source.vehicle_id, forecast.stops_to_target, batch.response_received_at,
               forecast.generated_at, source.quality_direction
        FROM forecast_evaluation evaluation
        JOIN seat_forecast forecast ON forecast.vehicle_observation_id = evaluation.vehicle_observation_id
         AND forecast.target_stop_order = evaluation.target_stop_order
        JOIN forecast_eligible_observation source ON source.id = evaluation.vehicle_observation_id
        JOIN observation_batch batch ON batch.id = source.observation_batch_id
        WHERE evaluation.scoring_state = 'PENDING' AND evaluation.route_version_id = :routeVersionId
          AND (evaluation.vehicle_observation_id, evaluation.target_stop_order)
              > (:afterObservationId, :afterStopOrder)
          AND (evaluation.vehicle_observation_id, evaluation.target_stop_order)
              <= (:lastObservationId, :lastStopOrder)
        ORDER BY evaluation.vehicle_observation_id, evaluation.target_stop_order
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
              arrival.id IS NOT NULL AND %s))
        RETURNING version.route_id, forecast.route_version_id, evaluation.vehicle_observation_id,
                  evaluation.target_stop_order, forecast.stops_to_target, forecast.seat_full_chance_raw,
                  evaluation.arrival_observation_id, evaluation.arrived_at, evaluation.seats_on_arrival,
                  evaluation.scoring_state, evaluation.scored_at,
                  forecast.quality_revision = quality.quality_revision AS usable_for_calibration,
                  source.vehicle_id AS prediction_vehicle_id, source.remaining_seats AS prediction_remaining_seats,
                  COALESCE((SELECT target_stop.boarding_allowed FROM route_stop target_stop
                      WHERE target_stop.route_version_id = forecast.route_version_id
                        AND target_stop.stop_order = forecast.target_stop_order), false) AS target_boarding_allowed
        """.formatted(EligibleObservationSql.ARRIVAL_MATCHES_SOURCE);

    private final JdbcClient jdbcClient;

    public JdbcForecastEvaluationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<Long> findRouteVersionIdsWithPendingForecasts() {
        return jdbcClient.sql("""
            SELECT DISTINCT route_version_id
            FROM forecast_evaluation
            WHERE scoring_state = 'PENDING'
            """).query(Long.class).list();
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
    public List<PendingForecast> findPending(final long routeVersionId, final int limit) {
        List<PendingForecast> pending = new ArrayList<>();
        PendingKey after = PendingKey.BEFORE_FIRST;
        while (pending.size() < limit) {
            List<PendingKey> keys = findPendingKeys(routeVersionId, after, limit);
            if (keys.isEmpty()) {
                break;
            }
            PendingKey last = keys.getLast();
            List<PendingForecast> eligible = findEligiblePending(routeVersionId, after, last);
            pending.addAll(eligible.subList(0, Math.min(eligible.size(), limit - pending.size())));
            after = last;
        }
        return pending;
    }

    private List<PendingKey> findPendingKeys(final long routeVersionId, PendingKey after, final int limit) {
        return jdbcClient.sql(SELECT_PENDING_KEYS)
            .param("routeVersionId", routeVersionId)
            .param("afterObservationId", after.vehicleObservationId())
            .param("afterStopOrder", after.targetStopOrder())
            .param("limit", limit)
            .query((row, index) -> new PendingKey(row.getLong("vehicle_observation_id"), row.getInt("target_stop_order")))
            .list();
    }

    private List<PendingForecast> findEligiblePending(final long routeVersionId, PendingKey after, PendingKey last) {
        return jdbcClient.sql(SELECT_PENDING)
            .param("routeVersionId", routeVersionId)
            .param("afterObservationId", after.vehicleObservationId())
            .param("afterStopOrder", after.targetStopOrder())
            .param("lastObservationId", last.vehicleObservationId())
            .param("lastStopOrder", last.targetStopOrder())
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
    public void addPending(final long routeVersionId, List<ForecastEvaluation> evaluations) {
        for (ForecastEvaluation evaluation : evaluations) {
            jdbcClient.sql(INSERT_PENDING)
                .params(evaluation.vehicleObservationId(), evaluation.targetStopOrder(), routeVersionId)
                .update();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SettledEvaluation> settle(List<ForecastEvaluation> evaluations) {
        if (evaluations.isEmpty()) {
            return List.of();
        }
        List<SettledEvaluation> newlySettled = new ArrayList<>();
        for (ForecastEvaluation evaluation : evaluations) {
            if (evaluation.state() == ScoringState.PENDING) {
                throw new IllegalArgumentException("완료된 평가만 저장할 수 있다");
            }
            newlySettled.addAll(parameters(COMPLETE, evaluation)
                .param("scoringState", evaluation.state().name())
                .param("seatsOnArrival", evaluation.result().seatsOnArrival())
                .param("scoredAt", offsetOf(evaluation.scoredAt()))
                .query((row, index) -> new SettledEvaluation(row.getLong("route_id"), row.getLong("route_version_id"),
                    row.getLong("vehicle_observation_id"), row.getInt("target_stop_order"),
                    row.getInt("stops_to_target"), row.getDouble("seat_full_chance_raw"),
                    ScoringState.valueOf(row.getString("scoring_state")),
                    row.getObject("arrival_observation_id", Long.class), row.getObject("seats_on_arrival", Integer.class),
                    instantOrNull(row.getObject("arrived_at", OffsetDateTime.class)),
                    row.getObject("scored_at", OffsetDateTime.class).toInstant(),
                    row.getBoolean("usable_for_calibration"), row.getString("prediction_vehicle_id"),
                    row.getObject("prediction_remaining_seats", Integer.class),
                    row.getBoolean("target_boarding_allowed")))
                .list());
        }
        return List.copyOf(newlySettled);
    }

    private JdbcClient.StatementSpec parameters(String sql, ForecastEvaluation evaluation) {
        return jdbcClient.sql(sql).param("vehicleObservationId", evaluation.vehicleObservationId())
            .param("targetStopOrder", evaluation.targetStopOrder())
            .param("arrivalObservationId", evaluation.result().arrivalObservationId());
    }

    private static Instant instantOrNull(OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static OffsetDateTime offsetOf(Instant timestamp) {
        return timestamp.atOffset(ZoneOffset.UTC);
    }

    private record PendingKey(long vehicleObservationId, int targetStopOrder) {

        private static final PendingKey BEFORE_FIRST = new PendingKey(0L, 0);
    }

}
