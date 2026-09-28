package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
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

    private static final String INSERT_PUBLICATION = """
        INSERT INTO forecast_publication (
            source_batch_id, route_version_id, model_deployment_id,
            demand_statistics_revision, quality_revision, observed_at, generated_at,
            published_at, prediction_count, provenance
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECORDED')
        RETURNING id
        """;

    private static final String UPDATE_PUBLICATION = """
        UPDATE forecast_publication
        SET model_deployment_id = ?, demand_statistics_revision = ?, quality_revision = ?, observed_at = ?,
            generated_at = ?, published_at = ?, provenance = 'RECORDED'
        WHERE id = ?
        """;

    /**
     * 예보 한 줄. 같은 (관측, 대상 순번) 이 이미 있으면 예보 값만 덮어쓴다.
     *
     * <p>재시도가 같은 판을 두 번 열어도 행이 하나만 남는다.
     */
    private static final String UPSERT_PREDICTION = """
        INSERT INTO seat_forecast (
            publication_id, vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
            model_deployment_id, demand_statistics_revision, seat_full_chance_raw, seat_full_chance,
            expected_seats, generated_at, quality_revision
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (vehicle_observation_id, target_stop_order) DO UPDATE SET
            publication_id = EXCLUDED.publication_id,
            route_version_id = EXCLUDED.route_version_id,
            stops_to_target = EXCLUDED.stops_to_target,
            model_deployment_id = EXCLUDED.model_deployment_id,
            demand_statistics_revision = EXCLUDED.demand_statistics_revision,
            seat_full_chance_raw = EXCLUDED.seat_full_chance_raw,
            seat_full_chance = EXCLUDED.seat_full_chance,
            expected_seats = EXCLUDED.expected_seats,
            generated_at = EXCLUDED.generated_at,
            quality_revision = EXCLUDED.quality_revision
        """;

    private static final String INSERT_PENDING_EVALUATION = """
        INSERT INTO forecast_evaluation (vehicle_observation_id, target_stop_order, route_version_id)
        VALUES (?, ?, ?)
        ON CONFLICT (vehicle_observation_id, target_stop_order) DO NOTHING
        """;

    private static final String COUNT_PREDICTIONS = """
        UPDATE forecast_publication SET prediction_count = (
            SELECT count(*) FROM seat_forecast forecast
            JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
            WHERE observation.observation_batch_id = ?)
        WHERE id = ?
        """;

    private final JdbcClient jdbc;

    public JdbcForecastPublicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(ForecastPublication publication) {
        final Optional<Long> existing = jdbc.sql("SELECT id FROM forecast_publication WHERE source_batch_id = ?")
            .param(publication.sourceBatchId()).query(Long.class).optional();
        final long publicationId = existing.orElseGet(() -> jdbc.sql(INSERT_PUBLICATION)
            .params(publication.sourceBatchId(), publication.routeVersionId(), publication.modelDeploymentId(),
                publication.demandStatisticsRevision(), publication.qualityRevision(),
                offset(publication.observedAt()), offset(publication.generatedAt()),
                offset(publication.publishedAt()), publication.predictionCount())
            .query(Long.class).single());
        existing.ifPresent(id -> jdbc.sql(UPDATE_PUBLICATION)
            .params(publication.modelDeploymentId(), publication.demandStatisticsRevision(),
                publication.qualityRevision(), offset(publication.observedAt()), offset(publication.generatedAt()),
                offset(publication.publishedAt()), id)
            .update());
        for (SeatForecast prediction : publication.predictions()) {
            jdbc.sql(UPSERT_PREDICTION)
                .params(publicationId, prediction.vehicleObservationId(), prediction.targetStopOrder(),
                    prediction.routeVersionId(), prediction.stopsToTarget(), prediction.modelDeploymentId(),
                    prediction.demandStatisticsRevision(), prediction.seatFullChanceRaw(),
                    prediction.seatFullChance(), prediction.expectedSeats(), offset(prediction.generatedAt()),
                    publication.qualityRevision())
                .update();
            jdbc.sql(INSERT_PENDING_EVALUATION)
                .params(prediction.vehicleObservationId(), prediction.targetStopOrder(), prediction.routeVersionId())
                .update();
        }
        existing.ifPresent(id -> jdbc.sql(COUNT_PREDICTIONS).params(publication.sourceBatchId(), id).update());
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
