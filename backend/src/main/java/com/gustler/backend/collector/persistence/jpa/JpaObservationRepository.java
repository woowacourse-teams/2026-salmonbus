package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.CollectedObservations;
import com.gustler.backend.collector.ObservationAttempt;
import com.gustler.backend.collector.ObservationBatchConclusion;
import com.gustler.backend.collector.ObservationBatchFailureCode;
import com.gustler.backend.collector.ObservationBatchOutcome;
import com.gustler.backend.collector.ObservationRepository;
import com.gustler.backend.collector.UpstreamObservationRow;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Repository;

@Repository
public class JpaObservationRepository implements ObservationRepository {

    private final CollectorObservationBatchRepository observationBatchRepository;
    private final CollectorVehicleObservationRepository vehicleObservationRepository;

    public JpaObservationRepository(
        CollectorObservationBatchRepository observationBatchRepository,
        CollectorVehicleObservationRepository vehicleObservationRepository
    ) {
        this.observationBatchRepository = observationBatchRepository;
        this.vehicleObservationRepository = vehicleObservationRepository;
    }

    @Override
    public long openReserved(
        ObservationAttempt attempt
    ) {
        return open(attempt, ObservationBatchOutcome.RESERVED, null);
    }

    @Override
    public long openNotReserved(
        ObservationAttempt attempt
    ) {
        return open(
            attempt,
            ObservationBatchOutcome.NOT_RESERVED,
            ObservationBatchFailureCode.LOCAL_QUOTA_EXHAUSTED);
    }

    @Override
    public void markDispatching(
        final long batchId,
        OffsetDateTime requestedAt
    ) {
        batchOf(batchId).markDispatching(requestedAt);
    }

    @Override
    public void abandonBeforeSend(
        final long batchId
    ) {
        batchOf(batchId).abandonBeforeSend();
    }

    @Override
    public void concludeWithoutRows(
        final long batchId,
        ObservationBatchConclusion conclusion,
        OffsetDateTime responseReceivedAt
    ) {
        batchOf(batchId).conclude(conclusion, responseReceivedAt);
    }

    @Override
    public void concludeWithRows(
        final long batchId,
        ObservationBatchConclusion conclusion,
        CollectedObservations collected,
        OffsetDateTime responseReceivedAt
    ) {
        ObservationBatchJpaEntity batch = batchOf(batchId);
        batch.conclude(conclusion, responseReceivedAt);
        batch.countRows(collected);

        vehicleObservationRepository.saveAll(collected.storableRows().stream()
            .map(row -> toEntity(batch, row))
            .toList());
    }

    /**
     * 같은 계획의 판이 이미 있으면 그 행을 다시 연다.
     * ux_batch_attempt 가 계획 하나에 묶음 하나를 강제해서 새로 넣으면 들어가지 않는다.
     */
    private long open(
        ObservationAttempt attempt,
        ObservationBatchOutcome outcome,
        ObservationBatchFailureCode failureCode
    ) {
        return observationBatchRepository
            .findByRouteVersionIdAndAttemptKey(attempt.routeVersionId(), attempt.attemptKey())
            .map(existing -> reopen(existing, outcome, failureCode))
            .orElseGet(() -> observationBatchRepository
                .save(new ObservationBatchJpaEntity(attempt, outcome, failureCode))
                .getId());
    }

    private long reopen(
        ObservationBatchJpaEntity existing,
        ObservationBatchOutcome outcome,
        ObservationBatchFailureCode failureCode
    ) {
        existing.reopen(outcome, failureCode);
        vehicleObservationRepository.deleteByObservationBatchId(existing.getId());
        return existing.getId();
    }

    private ObservationBatchJpaEntity batchOf(
        final long batchId
    ) {
        return observationBatchRepository.findById(batchId).orElseThrow();
    }

    private VehicleObservationJpaEntity toEntity(
        ObservationBatchJpaEntity batch,
        UpstreamObservationRow row
    ) {
        return new VehicleObservationJpaEntity(batch.getId(), batch.getRouteVersionId(), row);
    }
}
