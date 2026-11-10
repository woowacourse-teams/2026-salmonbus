package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.application.AfterCommitTelemetry;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.observations.api.CollectionInputs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 노선 잠금 안에서 발행과 입력 확정을 저장한다. 새 정산 작업은 DB trigger로 함께 등록된다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastBatchCommitter {
    private final ForecastPublicationRepository publications;
    private final RouteWorkAccess routeWork;
    private final CollectionInputs inputs;
    private ForecastTelemetry telemetry = ForecastTelemetry.NONE;

    public ForecastBatchCommitter(ForecastPublicationRepository publications, RouteWorkAccess routeWork,
        CollectionInputs inputs) {
        this.publications = publications;
        this.routeWork = routeWork;
        this.inputs = inputs;
    }

    @Autowired(required = false)
    public void setTelemetry(ForecastTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Transactional(readOnly = false)
    public int commit(PendingForecastBatch batch, ForecastPublication publication) {
        return commitPublication(batch, publication, false);
    }

    @Transactional(readOnly = false)
    public int commitConfirmed(PendingForecastBatch batch, ForecastPublication publication) {
        return commitPublication(batch, publication, true);
    }

    private int commitPublication(PendingForecastBatch batch, ForecastPublication publication, final boolean confirmed) {
        routeWork.lock(batch.routeVersionId());
        boolean first = publications.save(publication);
        if (!confirmed) {
            inputs.confirmInput(batch.observationBatchId(), publication.generatedAt());
        }
        if (first) {
            AfterCommitTelemetry.record(() -> telemetry.published(batch.routeId(), batch.routeVersionId(),
                batch.responseReceivedAt(), publication.predictionCount()));
        }
        return publication.predictionCount();
    }
}
