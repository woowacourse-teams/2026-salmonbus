package com.gustler.backend.forecasting.infrastructure.observations;

import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import java.util.Set;
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
public class VehicleCapacityCollectionAdapter implements CollectionQualityHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleCapacityCollectionAdapter.class);
    private final JdbcVehicleCapacityStore store;
    private final VehicleCapacityCache cache;

    private final ObjectProvider<ForecastTelemetry> telemetry;

    public VehicleCapacityCollectionAdapter(
        JdbcVehicleCapacityStore store, VehicleCapacityCache cache, ObjectProvider<ForecastTelemetry> telemetry
    ) {
        this.store = store;
        this.cache = cache;
        this.telemetry = telemetry;
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public Set<Long> observationsStored(VehicleObservationsStored batch) {
        if (batch.rows().stream().noneMatch(this::hasSeats)) {
            return Set.of();
        }
        JdbcVehicleCapacityStore.BatchAssessment assessment = store.assessBatch(batch.batchId());
        if (!assessment.raised().isEmpty() || !assessment.withheldObservationIds().isEmpty()) {
            registerCommittedAssessment(batch, assessment);
        }
        return assessment.withheldObservationIds();
    }

    private void registerCommittedAssessment(
        VehicleObservationsStored batch,
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
                reportWithheldSeats(batch, assessment);
            }
        });
    }

    private void reportWithheldSeats(
        VehicleObservationsStored batch,
        JdbcVehicleCapacityStore.BatchAssessment assessment
    ) {
        final int withheld = assessment.withheldObservationIds().size();
        if (withheld == 0) {
            return;
        }
        final int reviews = withheld - assessment.outliers();
        LOGGER.warn("event=observation_seat_quality batchId={} routeVersionId={} reviewCount={} outlierCount={}",
            batch.batchId(), batch.routeVersionId(), reviews, assessment.outliers());
        try {
            telemetry.getIfAvailable(() -> ForecastTelemetry.NONE)
                .collectionSeatQuality(reviews, assessment.outliers());
        } catch (RuntimeException failure) {
            LOGGER.warn("event=observation_seat_quality_metric_failed batchId={}", batch.batchId(), failure);
        }
    }

    private boolean hasSeats(VehicleObservationsStored.Row row) {
        Integer seats = row.remainingSeats();
        return seats != null && seats > 0;
    }
}
