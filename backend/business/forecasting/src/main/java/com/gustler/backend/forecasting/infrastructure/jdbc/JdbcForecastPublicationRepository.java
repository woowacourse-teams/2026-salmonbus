package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예보 발행 기록과 예보 행을 저장 경계에 반영한다. 관측·노선·모델·품질 판본을 함께 보존한다.
 */
@Repository
public class JdbcForecastPublicationRepository implements ForecastPublicationRepository {
    private static final String INSERT_PUBLICATION = """
        INSERT /* salmonbus:forecast_publication.insert_publication */ INTO forecast_publication (
            source_batch_id, route_version_id, model_deployment_id,
            demand_statistics_revision, quality_revision, observed_at, generated_at,
            published_at, prediction_count, provenance
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECORDED')
        RETURNING id
        """;

    private static final String UPDATE_PUBLICATION = """
        UPDATE /* salmonbus:forecast_publication.update_publication */ forecast_publication
        SET model_deployment_id = ?, demand_statistics_revision = ?, quality_revision = ?, observed_at = ?,
            generated_at = ?, published_at = ?, provenance = 'RECORDED'
        WHERE id = ?
        """;

    /**
     * 예보 한 줄. 같은 (관측, 대상 순번) 이 이미 있으면 예보 값만 덮어쓴다.
     *
     * <p>
     * 재시도가 같은 판을 두 번 열어도 행이 하나만 남는다.
     */
    // 재발행은 최초 평가 선택을 유지한다. 비활성화로 과거 생략분이 다시 등록되지 않는다.
    private static final String UPSERT_PREDICTION = """
        INSERT /* salmonbus:forecast_publication.upsert_prediction */ INTO seat_forecast (
            publication_id, vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
            model_deployment_id, demand_statistics_revision, seat_full_chance_raw, seat_full_chance,
            expected_seats, generated_at, quality_revision, evaluation_detail_required,
            seat_target, probability_target, label_version, feature_snapshot, inference_bundle_hash
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
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
            quality_revision = EXCLUDED.quality_revision,
            seat_target = EXCLUDED.seat_target, probability_target = EXCLUDED.probability_target,
            label_version = EXCLUDED.label_version, feature_snapshot = EXCLUDED.feature_snapshot,
            inference_bundle_hash = EXCLUDED.inference_bundle_hash
        """;

    private static final String COUNT_PREDICTIONS = """
        UPDATE /* salmonbus:forecast_publication.count_predictions */ forecast_publication SET prediction_count = (
            SELECT count(*) FROM seat_forecast forecast
            JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
            WHERE observation.observation_batch_id = ?)
        WHERE id = ?
        """;

    private final JdbcClient jdbc;
    private final JdbcOperations batches;

    public JdbcForecastPublicationRepository(JdbcClient jdbc, JdbcOperations batches) {
        this.jdbc = jdbc;
        this.batches = batches;
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public boolean save(ForecastPublication publication) {
        final boolean archived = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM operational_archive_boundary
                WHERE route_version_id=? AND closed_before>?)
            """).params(publication.routeVersionId(), offset(publication.observedAt())).query(Boolean.class).single();
        if (archived) {
            throw new IllegalStateException("이관 대상으로 확정한 과거 예보는 운영에서 재발행하지 않는다");
        }
        Optional<ExistingPublication> previous = jdbc.sql(
            """
                SELECT p.id, p.model_deployment_id,
                    (old.calculation_version='alighting-online-v1' OR incoming.calculation_version='alighting-online-v1') AS alighting
                FROM forecast_publication p JOIN model_deployment old ON old.id=p.model_deployment_id
                JOIN model_deployment incoming ON incoming.id=? WHERE p.source_batch_id=? FOR UPDATE OF p
                """)
            .params(publication.modelDeploymentId(), publication.sourceBatchId())
            .query((row, index) -> new ExistingPublication(row.getLong("id"), row.getLong("model_deployment_id"),
                row.getBoolean("alighting")))
            .optional();
        previous.ifPresent(existing -> {
            if (existing.alighting() && existing.modelDeploymentId() != publication.modelDeploymentId()) {
                throw new IllegalStateException("이미 발행한 관측을 다른 모델로 덮어쓸 수 없다. 독립 재생 DB를 사용해야 한다");
            }
        });
        Optional<Long> existing = previous.map(ExistingPublication::id);
        final long publicationId = existing.orElseGet(() -> jdbc.sql(INSERT_PUBLICATION)
            .params(publication.sourceBatchId(), publication.routeVersionId(), publication.modelDeploymentId(),
                publication.demandStatisticsRevision(), publication.qualityRevision(), offset(publication.observedAt()),
                offset(publication.generatedAt()), offset(publication.publishedAt()), publication.predictionCount())
            .query(Long.class).single());
        existing.ifPresent(id -> jdbc.sql(UPDATE_PUBLICATION)
            .params(publication.modelDeploymentId(), publication.demandStatisticsRevision(),
                publication.qualityRevision(), offset(publication.observedAt()), offset(publication.generatedAt()),
                offset(publication.publishedAt()), id)
            .update());
        List<Object[]> rows = publication.predictions()
            .stream()
            .map(prediction -> new Object[] { publicationId, prediction.vehicleObservationId(),
                prediction.targetStopOrder(), prediction.routeVersionId(), prediction.stopsToTarget(),
                prediction.modelDeploymentId(), prediction.demandStatisticsRevision(), prediction.seatFullChanceRaw(),
                prediction.seatFullChance(), prediction.expectedSeats(), offset(prediction.generatedAt()),
                publication.qualityRevision(), prediction.evaluationDetailRequired(), prediction.seatTarget(),
                prediction.probabilityTarget(), prediction.labelVersion(), prediction.featureSnapshot(),
                prediction.inferenceBundleHash() })
            .toList();
        if (!rows.isEmpty()) {
            batches.batchUpdate(UPSERT_PREDICTION, rows);
        }
        existing.ifPresent(id -> jdbc.sql(COUNT_PREDICTIONS).params(publication.sourceBatchId(), id).update());
        return existing.isEmpty();
    }

    private record ExistingPublication(long id, long modelDeploymentId, boolean alighting) {
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
