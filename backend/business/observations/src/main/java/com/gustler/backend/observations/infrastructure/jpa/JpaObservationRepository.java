package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.domain.StoredObservations;
import com.gustler.backend.observations.domain.CollectedObservations;
import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.ObservationBatchConclusion;
import com.gustler.backend.observations.domain.ObservationRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaObservationRepository implements ObservationRepository {
    private final CollectorObservationBatchRepository batches;
    private final CollectorVehicleObservationRepository observations;

    public JpaObservationRepository(CollectorObservationBatchRepository batches,
                                    CollectorVehicleObservationRepository observations) {
        this.batches = batches;
        this.observations = observations;
    }

    @Override
    public long openReserved(CollectionPlan plan) {
        return open(plan, true);
    }

    @Override
    public long openNotReserved(CollectionPlan plan) {
        return open(plan, false);
    }

    @Override
    public void markDispatching(final long batchId, OffsetDateTime requestedAt) {
        ObservationBatchJpaEntity entity = batchOf(batchId);
        CollectionBatch batch = entity.toDomain();
        batch.dispatch(requestedAt);
        entity.apply(batch);
    }

    @Override
    public void abandonBeforeSend(final long batchId) {
        ObservationBatchJpaEntity entity = batchOf(batchId);
        CollectionBatch batch = entity.toDomain();
        batch.abandonBeforeSend();
        entity.apply(batch);
    }

    @Override
    public void concludeWithoutRows(final long batchId, ObservationBatchConclusion conclusion,
                                    OffsetDateTime responseReceivedAt) {
        ObservationBatchJpaEntity entity = batchOf(batchId);
        CollectionBatch batch = entity.toDomain();
        batch.conclude(conclusion, responseReceivedAt);
        entity.apply(batch);
    }

    @Override
    public StoredObservations concludeWithRows(final long batchId, ObservationBatchConclusion conclusion,
                                               CollectedObservations collected,
                                               OffsetDateTime responseReceivedAt) {
        ObservationBatchJpaEntity entity = batchOf(batchId);
        CollectionBatch batch = entity.toDomain();
        batch.conclude(conclusion, responseReceivedAt);
        batch.countRows(collected);
        entity.apply(batch);
        var stored = observations.saveAll(collected.storableRows().stream()
            .map(row -> new VehicleObservationJpaEntity(batchId, entity.getRouteVersionId(), row)).toList());
        return new StoredObservations(batchId, entity.getRouteVersionId(), responseReceivedAt.toInstant(),
            stored.stream().map(VehicleObservationJpaEntity::storedRow).toList());
    }

    /**
     * 같은 계획의 판이 이미 있으면 그 행을 다시 연다.
     * ux_batch_attempt 가 계획 하나에 묶음 하나를 강제해서 새로 넣으면 들어가지 않는다.
     */
    private long open(CollectionPlan plan, final boolean reserved) {
        var existing = batches.findByRouteVersionIdAndAttemptKey(plan.routeVersionId(), plan.attemptKey());
        if (existing.isPresent()) {
            ObservationBatchJpaEntity entity = existing.orElseThrow();
            CollectionBatch batch = entity.toDomain();
            batch.startAttempt(reserved);
            entity.apply(batch);
            observations.deleteByObservationBatchId(entity.getId());
            return entity.getId();
        }
        return batches.save(new ObservationBatchJpaEntity(CollectionBatch.start(plan, reserved))).getId();
    }

    private ObservationBatchJpaEntity batchOf(final long batchId) {
        return batches.findById(batchId).orElseThrow();
    }
}
