package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.application.AfterCommitTelemetry;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.observations.api.CollectionInputs;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 계산에 사용한 품질 버전이 유지될 때만 예보와 정산 작업을 함께 확정한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastBatchCommitter {
    private static final Logger log = LoggerFactory.getLogger(ForecastBatchCommitter.class);
    private final ForecastPublicationRepository publications;
    private final EvaluationWorkStore work;
    private final RouteDataQualityAccess quality;
    private final CollectionInputs inputs;
    private ForecastTelemetry telemetry = ForecastTelemetry.NONE;

    public ForecastBatchCommitter(ForecastPublicationRepository publications, EvaluationWorkStore work,
        RouteDataQualityAccess quality, CollectionInputs inputs) {
        this.publications = publications;
        this.work = work;
        this.quality = quality;
        this.inputs = inputs;
    }

    @Autowired(required = false)
    public void setTelemetry(ForecastTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Transactional
    public int commit(PendingForecastBatch batch, ForecastPublication publication) {
        quality.ensureExists(batch.routeVersionId());
        long currentRevision = quality.current(batch.routeVersionId());
        if (currentRevision != publication.qualityRevision()) {
            log.info("event=forecast_publication_skipped reason=QUALITY_CHANGED batchId={} routeVersionId={} expectedRevision={} actualRevision={}",
                batch.observationBatchId(), batch.routeVersionId(), publication.qualityRevision(), currentRevision);
            return 0;
        }

        publications.save(publication);
        List<Long> observations = publication.predictions().stream()
            .map(SeatForecast::vehicleObservationId).distinct().toList();
        for (int from = 0; from < observations.size(); from += 100) {
            work.register(batch.routeVersionId(), observations.subList(from, Math.min(from + 100, observations.size())));
        }
        inputs.confirmInput(batch.observationBatchId(), publication.generatedAt());
        AfterCommitTelemetry.record(() -> telemetry.published(batch.routeId(), batch.routeVersionId(),
            batch.responseReceivedAt(), publication.predictionCount()));
        return publication.predictionCount();
    }
}
