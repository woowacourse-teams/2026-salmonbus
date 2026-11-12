package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.CollectionBatchRepository;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.StoredObservations.Row;
import com.gustler.backend.observations.domain.UpstreamObservationRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 계획의 배치와 정규화된 관측을 저장한다.
 * 같은 계획의 재시도는 기존 배치의 저장 계약을 유지한다.
 */
@Repository
@Transactional(readOnly = false, propagation = Propagation.MANDATORY)
public class JpaCollectionBatchRepository implements CollectionBatchRepository {
    private final ObservationBatchEntityRepository batches;
    private final VehicleObservationEntityRepository observations;

    public JpaCollectionBatchRepository(ObservationBatchEntityRepository batches,
        VehicleObservationEntityRepository observations) {
        this.batches = batches;
        this.observations = observations;
    }

    @Override
    public Optional<CollectionBatch> findByPlan(CollectionPlan plan) {
        return batches.findByRouteVersionIdAndAttemptKey(plan.routeVersionId(), plan.attemptKey())
            .map(ObservationBatchJpaEntity::toDomain);
    }

    @Override
    public CollectionBatch getById(final long batchId) {
        return batches.findById(batchId).orElseThrow().toDomain();
    }

    @Override
    public void confirmInputOnce(final long batchId, OffsetDateTime at) {
        final int changed = batches.confirmInputOnce(batchId, at);
        if (changed == 0 && !batches.existsById(batchId)) {
            throw new IllegalArgumentException("수집 배치가 없다: " + batchId);
        }
    }

    @Override
    public long save(CollectionBatch batch) {
        if (batch.id() == null) {
            return batches.save(new ObservationBatchJpaEntity(batch)).getId();
        }
        batches.findById(batch.id()).orElseThrow().apply(batch);
        batches.flush();
        return batch.id();
    }

    @Override
    public int closeUnfinished(final long batchId) {
        return batches.closeUnfinished(batchId);
    }

    @Override
    public List<Long> lockUnfinished(final int limit) {
        return batches.lockUnfinished(limit);
    }

    @Override
    public void deleteObservationsOf(final long batchId) {
        observations.deleteByObservationBatchId(batchId);
    }

    @Override
    public List<Row> saveObservations(CollectionBatch batch, List<UpstreamObservationRow> rows) {
        return observations.saveAll(rows.stream()
            .map(row -> new VehicleObservationJpaEntity(batch.id(), batch.routeVersionId(), row))
            .toList())
            .stream()
            .map(VehicleObservationJpaEntity::storedRow)
            .toList();
    }
}
