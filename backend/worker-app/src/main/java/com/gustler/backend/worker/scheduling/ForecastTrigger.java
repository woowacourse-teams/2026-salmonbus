package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.publication.PublishPendingForecasts;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 관측 저장 알림과 정기 호출을 옛 예보 발행 경로에 연결한다.
 * 실제 실행과 처리 결과는 기존 업무 서비스에 맡긴다.
 *
 * <p>본선이 옛 경로({@code forecast.pipeline=v1}, 기본값)일 때만 등록한다. {@code v2}에서는 {@link PipelineForecastTrigger}가
 * 같은 자리에서 새 경로로 넘긴다.
 */
@Component
@ConditionalOnExpression(ForecastTrigger.LEGACY_PIPELINE)
public class ForecastTrigger implements ObservationDeliveryHook {
    /** 옛 경로가 본선인 조건. */
    static final String LEGACY_PIPELINE = "${forecast.enabled:false}"
        + " and '${forecast.pipeline:v1}'.equalsIgnoreCase('v1')";
    private final PublishPendingForecasts service;
    private final TaskExecutor forecastTaskScheduler;
    private final Set<Long> pendingRoutes = ConcurrentHashMap.newKeySet();

    public ForecastTrigger(
        PublishPendingForecasts service,
        @Qualifier("forecastTaskScheduler") TaskExecutor forecastTaskScheduler
    ) {
        this.service = service;
        this.forecastTaskScheduler = forecastTaskScheduler;
    }

    @Scheduled(fixedDelayString = "${forecast.interval}", scheduler = "forecastTaskScheduler")
    public void writeForecasts() {
        service.writeForecasts();
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public void record(ObservationDeliveryBatch batch) {
        if (batch.kind() != Kind.STORED) {
            return;
        }
        final long routeVersionId = batch.routeVersionId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                onObservationStored(routeVersionId);
            }
        });
    }

    void onObservationStored(final long routeVersionId) {
        if (!pendingRoutes.add(routeVersionId)) {
            return;
        }
        try {
            forecastTaskScheduler.execute(() -> writeForecastsOf(routeVersionId));
        } catch (TaskRejectedException stopping) {
            pendingRoutes.remove(routeVersionId);
        }
    }

    private void writeForecastsOf(final long routeVersionId) {
        pendingRoutes.remove(routeVersionId);
        try {
            service.writeForecastsOfRoute(routeVersionId);
        } catch (RuntimeException alreadyLogged) {
            return;
        }
    }
}
