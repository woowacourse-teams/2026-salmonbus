package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 평가 테스트가 발행 유스케이스와 독립적으로 사용할 예측과 PENDING 평가를 준비한다. */
final class ForecastEvaluationTestData {

    private ForecastEvaluationTestData() {
    }

    static void saveForecasts(JdbcClient jdbc, List<SeatForecast> forecasts) {
        for (SeatForecast forecast : forecasts) {
            jdbc.sql("""
                INSERT INTO route_data_quality(route_id)
                SELECT route_id FROM route_version WHERE id = :version
                ON CONFLICT DO NOTHING
                """).param("version", forecast.routeVersionId()).update();
            long publicationId = jdbc.sql("""
                INSERT INTO forecast_publication(source_batch_id, source_attempt_number, route_version_id,
                    model_deployment_id, demand_statistics_revision, quality_revision,
                    observed_at, generated_at, published_at, prediction_count)
                SELECT batch.id, batch.attempt_number, batch.route_version_id,
                    :model, :revision, quality.quality_revision,
                    batch.response_received_at, :generatedAt, :generatedAt, 0
                FROM vehicle_observation observation
                JOIN observation_batch batch ON batch.id = observation.observation_batch_id
                JOIN route_version version ON version.id = batch.route_version_id
                JOIN route_data_quality quality ON quality.route_id = version.route_id
                WHERE observation.id = :observation
                ON CONFLICT(source_batch_id) DO UPDATE SET source_batch_id = EXCLUDED.source_batch_id
                RETURNING id
                """).param("model", forecast.modelDeploymentId()).param("revision", forecast.demandStatisticsRevision())
                .param("generatedAt", OffsetDateTime.ofInstant(forecast.generatedAt(), ZoneOffset.UTC))
                .param("observation", forecast.vehicleObservationId()).query(Long.class).single();
            jdbc.sql("""
                INSERT INTO seat_forecast(vehicle_observation_id, target_stop_order, route_version_id,
                    stops_to_target, model_deployment_id, demand_statistics_revision, seat_full_chance_raw,
                    seat_full_chance, expected_seats, generated_at, quality_revision, publication_id)
                SELECT :observation, :target, :version, :distance, :model, :revision,
                    :rawChance, :chance, :seats, :generatedAt, publication.quality_revision, publication.id
                FROM forecast_publication publication WHERE publication.id = :publication
                """).param("observation", forecast.vehicleObservationId()).param("target", forecast.targetStopOrder())
                .param("version", forecast.routeVersionId()).param("distance", forecast.stopsToTarget())
                .param("model", forecast.modelDeploymentId()).param("revision", forecast.demandStatisticsRevision())
                .param("rawChance", forecast.seatFullChanceRaw()).param("chance", forecast.seatFullChance())
                .param("seats", forecast.expectedSeats()).param("generatedAt", OffsetDateTime.ofInstant(forecast.generatedAt(), ZoneOffset.UTC))
                .param("publication", publicationId).update();
            jdbc.sql("""
                INSERT INTO forecast_evaluation(vehicle_observation_id, target_stop_order, route_version_id)
                VALUES(:observation, :target, :version)
                """).param("observation", forecast.vehicleObservationId()).param("target", forecast.targetStopOrder())
                .param("version", forecast.routeVersionId()).update();
            jdbc.sql("""
                UPDATE forecast_publication SET prediction_count = (
                    SELECT count(*) FROM seat_forecast WHERE publication_id = :publication)
                WHERE id = :publication
                """).param("publication", publicationId).update();
        }
    }
}
