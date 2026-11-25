package com.gustler.backend.forecasting.infrastructure.observations;

import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.observations.api.ObservationSeatQualityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 배치의 좌석을 판정하고 커밋된 정원만 Worker 메모리에 반영한다. */
@Component
public class VehicleCapacitySeatQualityChecker implements ObservationSeatQualityChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleCapacitySeatQualityChecker.class);

    private final JdbcVehicleCapacityStore store;
    private final VehicleCapacityCache cache;
    private final ObjectProvider<ForecastTelemetry> telemetry;

    public VehicleCapacitySeatQualityChecker(
        JdbcVehicleCapacityStore store,
        VehicleCapacityCache cache,
        ObjectProvider<ForecastTelemetry> telemetry
    ) {
        this.store = store;
        this.cache = cache;
        this.telemetry = telemetry;
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public void observationsStored(final long batchId, final long routeVersionId, final boolean hasPositiveSeats) {
        if (!hasPositiveSeats) {
            return;
        }

        JdbcVehicleCapacityStore.BatchAssessment assessment = store.assessBatch(batchId);
        if (!assessment.raised().isEmpty() || !assessment.withheldObservationIds().isEmpty()) {
            registerCommittedAssessment(batchId, routeVersionId, assessment);
        }
    }

    private void registerCommittedAssessment(
        final long batchId,
        final long routeVersionId,
        JdbcVehicleCapacityStore.BatchAssessment assessment
    ) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
            }

            @Override
            public void afterCommit() {
                cache.remember(assessment.raised());
                reportWithheldSeats(batchId, routeVersionId, assessment);
            }
        });
    }

    private void reportWithheldSeats(
        final long batchId,
        final long routeVersionId,
        JdbcVehicleCapacityStore.BatchAssessment assessment
    ) {
        final int withheldSeatCount = assessment.withheldObservationIds().size();
        if (withheldSeatCount == 0) {
            return;
        }

        final int reviewCount = withheldSeatCount - assessment.outliers();

        LOGGER.warn(
                "event=observation_seat_quality batchId={} routeVersionId={} reviewCount={} outlierCount={}",
                batchId,
                routeVersionId,
                reviewCount,
                assessment.outliers()
        );

        try {
            telemetry.getIfAvailable(() -> ForecastTelemetry.NONE)
                .collectionSeatQuality(reviewCount, assessment.outliers());
        } catch (RuntimeException failure) {
            LOGGER.warn("event=observation_seat_quality_metric_failed batchId={}", batchId, failure);
        }
    }
}
