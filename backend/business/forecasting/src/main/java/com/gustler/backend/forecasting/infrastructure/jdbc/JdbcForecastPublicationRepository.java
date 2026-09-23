package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
import com.gustler.backend.forecasting.domain.publication.PublishedForecast;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcForecastPublicationRepository implements ForecastPublicationRepository {

    private final JdbcClient jdbc;

    public JdbcForecastPublicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PublishedForecast> findBySourceBatchId(final long sourceBatchId) {
        return jdbc.sql("SELECT id, prediction_count FROM forecast_publication WHERE source_batch_id = ?")
            .param(sourceBatchId)
            .query((row, index) -> new PublishedForecast(row.getLong("id"), row.getInt("prediction_count")))
            .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PublishedForecast save(ForecastPublication publication) {
        final long publicationId = jdbc.sql("""
            INSERT INTO forecast_publication (
                source_batch_id, source_attempt_number, route_version_id, model_deployment_id,
                demand_statistics_revision, quality_revision, observed_at, generated_at,
                published_at, prediction_count, provenance
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECORDED')
            RETURNING id
            """)
            .params(publication.sourceBatchId(), publication.sourceAttemptNumber(), publication.routeVersionId(),
                publication.modelDeploymentId(), publication.demandStatisticsRevision(), publication.qualityRevision(),
                offset(publication.observedAt()), offset(publication.generatedAt()), offset(publication.publishedAt()),
                publication.predictionCount())
            .query(Long.class).single();
        for (SeatForecast prediction : publication.predictions()) {
            insertPrediction(publicationId, publication, prediction);
            jdbc.sql("""
                INSERT INTO forecast_evaluation (vehicle_observation_id, target_stop_order, route_version_id)
                VALUES (?, ?, ?)
                """)
                .params(prediction.vehicleObservationId(), prediction.targetStopOrder(), prediction.routeVersionId())
                .update();
        }
        return new PublishedForecast(publicationId, publication.predictionCount());
    }

    private void insertPrediction(
        final long publicationId,
        ForecastPublication publication,
        SeatForecast prediction
    ) {
        // 원 관측이 발행의 수집 배치에 속하는지도 확인한다. 잘못된 묶음은 전체 발행을 롤백한다.
        final int inserted = jdbc.sql("""
            INSERT INTO seat_forecast (
                publication_id, vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
                model_deployment_id, demand_statistics_revision, seat_full_chance_raw, seat_full_chance,
                expected_seats, generated_at, quality_revision
            )
            SELECT ?, observation.id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            FROM vehicle_observation observation
            WHERE observation.id = ? AND observation.observation_batch_id = ? AND observation.route_version_id = ?
            """)
            .params(publicationId, prediction.targetStopOrder(), prediction.routeVersionId(), prediction.stopsToTarget(),
                prediction.modelDeploymentId(), prediction.demandStatisticsRevision(), prediction.seatFullChanceRaw(),
                prediction.seatFullChance(), prediction.expectedSeats(), offset(prediction.generatedAt()),
                publication.qualityRevision(), prediction.vehicleObservationId(), publication.sourceBatchId(),
                publication.routeVersionId())
            .update();
        if (inserted != 1) {
            throw new IllegalArgumentException("예측의 원 관측이 발행 대상 수집 배치에 속하지 않는다: "
                + prediction.vehicleObservationId());
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
