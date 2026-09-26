package com.gustler.backend.forecasting.infrastructure.jdbc;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 통계 조회 테스트에 필요한 발행·예측·평가를 새 저장 구조로 준비한다. */
final class StatisticsForecastFixture {

    private StatisticsForecastFixture() {
    }

    static void insertPending(
        JdbcClient jdbc,
        final long observationId,
        final int targetStopOrder,
        final int stopsToTarget,
        final long modelDeploymentId,
        final int statisticsRevision,
        final double rawChance,
        final double chance,
        final double expectedSeats,
        OffsetDateTime generatedAt
    ) {
        final long publicationId = jdbc.sql("""
                INSERT INTO forecast_publication (
                    source_batch_id, source_attempt_number, route_version_id, model_deployment_id,
                    demand_statistics_revision, quality_revision, observed_at, generated_at,
                    published_at, prediction_count
                )
                SELECT batch.id, batch.attempt_number, batch.route_version_id, ?, ?,
                       COALESCE(quality.quality_revision, 1), batch.response_received_at, ?, ?, 1
                FROM vehicle_observation observation
                JOIN observation_batch batch ON batch.id = observation.observation_batch_id
                JOIN route_version version ON version.id = batch.route_version_id
                LEFT JOIN route_data_quality quality ON quality.route_id = version.route_id
                WHERE observation.id = ?
                RETURNING id
                """)
            .params(modelDeploymentId, statisticsRevision, generatedAt, generatedAt, observationId)
            .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO seat_forecast (
                    vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
                    model_deployment_id, demand_statistics_revision, seat_full_chance_raw,
                    seat_full_chance, expected_seats, generated_at, quality_revision, publication_id
                )
                SELECT ?, ?, route_version_id, ?, model_deployment_id, demand_statistics_revision,
                       ?, ?, ?, generated_at, quality_revision, id
                FROM forecast_publication WHERE id = ?
                """)
            .params(observationId, targetStopOrder, stopsToTarget, rawChance, chance, expectedSeats, publicationId)
            .update();
        jdbc.sql("""
                INSERT INTO forecast_evaluation (vehicle_observation_id, target_stop_order, route_version_id)
                SELECT vehicle_observation_id, target_stop_order, route_version_id
                FROM seat_forecast WHERE vehicle_observation_id = ? AND target_stop_order = ?
                """)
            .params(observationId, targetStopOrder).update();
    }

    static void settle(
        JdbcClient jdbc,
        final long observationId,
        final int targetStopOrder,
        final long arrivalObservationId,
        OffsetDateTime scoredAt
    ) {
        jdbc.sql("""
                UPDATE forecast_evaluation evaluation
                SET scoring_state = 'SETTLED', arrival_observation_id = arrival.id,
                    seats_on_arrival = arrival.remaining_seats, scored_at = ?,
                    arrived_at = batch.response_received_at,
                    arrival_route_version_id = arrival.route_version_id,
                    arrival_vehicle_id = arrival.vehicle_id, arrival_stop_order = arrival.stop_order,
                    arrival_running_state = arrival.running_state,
                    arrival_remaining_seats = arrival.remaining_seats,
                    arrival_seat_unknown_reason = arrival.seat_unknown_reason,
                    arrival_vehicle_trip_key = arrival.vehicle_trip_key,
                    arrival_quality_direction = arrival.quality_direction
                FROM forecast_observation_quality arrival
                JOIN observation_batch batch ON batch.id = arrival.observation_batch_id
                WHERE arrival.id = ? AND evaluation.vehicle_observation_id = ?
                  AND evaluation.target_stop_order = ?
                """)
            .params(scoredAt, arrivalObservationId, observationId, targetStopOrder).update();
    }
}
