package com.gustler.backend.api.salmon.offline;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 오프라인 입력 폴더가 담는 발행 표시({@code forecast_publication})와 좌석 예보({@code seat_forecast}) 줄을 시험 DB에 넣는다.
 *
 * <p>통계 판은 0, 품질 판은 1이고 좌석 예보는 하차 반영 목표로 넣는다. 제품 조회가 같은 예측을 예보 기록에서 읽을 때와 같은
 * 판본 표시와 예측 목표가 나온다.
 */
final class OfflineForecastRows {
    private final JdbcClient jdbcClient;

    OfflineForecastRows(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void insertPublication(
        final long batchId,
        final long modelDeploymentId,
        OffsetDateTime generatedAt,
        OffsetDateTime publishedAt
    ) {
        jdbcClient.sql("""
                INSERT INTO forecast_publication (
                    source_batch_id, route_version_id,
                    model_deployment_id, demand_statistics_revision, quality_revision,
                    observed_at, generated_at, published_at, prediction_count
                )
                SELECT id, route_version_id,
                       :modelDeploymentId, 0, 1,
                       response_received_at, :generatedAt, :publishedAt, 0
                FROM observation_batch WHERE id = :batchId
                """)
            .param("batchId", batchId)
            .param("modelDeploymentId", modelDeploymentId)
            .param("generatedAt", generatedAt)
            .param("publishedAt", publishedAt)
            .update();
    }

    void insertForecast(
        final long observationId,
        final int targetStopOrder,
        final int stopsToTarget,
        final double seatFullChance,
        Double expectedSeats
    ) {
        jdbcClient.sql("""
                INSERT INTO seat_forecast (
                    publication_id, vehicle_observation_id, target_stop_order, route_version_id,
                    stops_to_target, model_deployment_id, demand_statistics_revision,
                    seat_full_chance_raw, seat_full_chance, expected_seats,
                    generated_at, quality_revision, seat_target, probability_target,
                    label_version, feature_snapshot, inference_bundle_hash
                )
                SELECT publication.id, observation.id, :targetStopOrder, observation.route_version_id,
                       :stopsToTarget, publication.model_deployment_id, 0,
                       :seatFullChance, :seatFullChance, :expectedSeats,
                       publication.generated_at, 1, 'AFTER_ALIGHTING_ESTIMATE', 'DEPARTURE_ZERO',
                       'alighting-identity-safe-v1', '{}'::jsonb, :bundleHash
                FROM vehicle_observation observation
                JOIN forecast_publication publication
                    ON publication.source_batch_id = observation.observation_batch_id
                WHERE observation.id = :observationId
                """)
            .param("observationId", observationId)
            .param("targetStopOrder", targetStopOrder)
            .param("stopsToTarget", stopsToTarget)
            .param("seatFullChance", seatFullChance)
            .param("expectedSeats", expectedSeats)
            .param("bundleHash", "b".repeat(64))
            .update();
        jdbcClient.sql("""
                UPDATE forecast_publication SET prediction_count = prediction_count + 1
                FROM vehicle_observation observation
                WHERE observation.id = :observationId
                  AND forecast_publication.source_batch_id = observation.observation_batch_id
                """)
            .param("observationId", observationId)
            .update();
    }
}
