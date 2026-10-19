package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.publication.PublishPendingForecasts;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastTrigger implements ObservationDeliveryHook {
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
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(ObservationDeliveryBatch batch) {
        if (batch.kind() != ObservationDeliveryBatch.Kind.STORED) {
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
