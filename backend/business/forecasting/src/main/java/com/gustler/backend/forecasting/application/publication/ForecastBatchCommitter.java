package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.application.AfterCommitTelemetry;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore;
import com.gustler.backend.forecasting.application.publication.EvaluationSamplingSelector.Selection;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.routeoperations.api.RouteOperations;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 계산에 사용한 품질 버전이 유지될 때만 예보와 정산 작업을 함께 확정한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastBatchCommitter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastBatchCommitter.class);
    private final ForecastPublicationRepository publications;
    private final EvaluationWorkStore work;
    private final RouteDataQualityAccess quality;
    private final CollectionInputs inputs;
    private final EvaluationSamplingSelector sampling;
    private ForecastTelemetry telemetry = ForecastTelemetry.NONE;

    private RouteOperations operations;

    public ForecastBatchCommitter(
        ForecastPublicationRepository publications,
        EvaluationWorkStore work,
        RouteDataQualityAccess quality,
        CollectionInputs inputs,
        EvaluationSamplingSelector sampling
    ) {
        this.publications = publications;
        this.work = work;
        this.quality = quality;
        this.inputs = inputs;
        this.sampling = sampling;
    }

    @Autowired(required = false)
    public void setOperations(RouteOperations value) {
        operations = value;
    }

    @Autowired(required = false)
    public void setTelemetry(ForecastTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * 품질 판본이 같은 경우에만 발행·평가 작업·입력 확정을 함께 저장한다. 표본 기준과 성공 지표는 실제 커밋 후 갱신한다.
     */
    @Transactional(readOnly = false)
    public int commit(PendingForecastBatch batch, ForecastPublication publication) {
        return commit(batch, publication, List.of());
    }

    /**
     * 품질 판본이 같은 경우에만 발행·평가 작업·입력 확정을 함께 저장한다. 표본 기준과 성공 지표는 실제 커밋 후 갱신한다.
     */
    @Transactional(readOnly = false)
    public int commit(
        PendingForecastBatch batch,
        ForecastPublication publication,
        List<EvaluationSamplingSource> sources
    ) {
        if (operations != null
            && !operations.allowPublication(
                batch.routeVersionId(), batch.responseReceivedAt())) {
            return 0;
        }
        quality.ensureExists(batch.routeVersionId());
        final long currentRevision = quality.current(batch.routeVersionId());
        if (currentRevision != publication.qualityRevision()) {
            LOGGER.info(
                "event=forecast_publication_skipped reason=QUALITY_CHANGED batchId={} routeVersionId={} expectedRevision={} actualRevision={}",
                batch.observationBatchId(), batch.routeVersionId(), publication.qualityRevision(), currentRevision);
            return 0;
        }

        Selection selection = sampling.select(publication, sources);
        final boolean firstPublication = publications.save(selection.publication());
        if (operations != null && selection.publication().predictionCount() > 0) {
            operations.publicationCommitted(batch.routeVersionId(), batch.responseReceivedAt());
        }
        List<Long> observations = selection.publication()
            .predictions()
            .stream()
            .map(SeatForecast::vehicleObservationId)
            .distinct()
            .toList();
        for (int from = 0; from < observations.size(); from += 100) {
            work.register(batch.routeVersionId(),
                observations.subList(from, Math.min(from + 100, observations.size())));
        }
        inputs.confirmInput(batch.observationBatchId(), publication.generatedAt());
        // 재발행은 DB에 남아 있는 최초 선택을 유지하므로 새 표본 기준으로 삼지 않는다.
        if (firstPublication && !selection.advances().isEmpty()) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("평가 표본 확정에는 실제 트랜잭션이 필요하다");
            }
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sampling.committed(selection);
                    }
                });
        }
        AfterCommitTelemetry.record(() -> telemetry.published(batch.routeId(), batch.routeVersionId(),
            batch.responseReceivedAt(), publication.predictionCount()));
        return publication.predictionCount();
    }
}
