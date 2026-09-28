package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.CollectionBatchRepository;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.StoredObservations;
import com.gustler.backend.observations.domain.UpstreamObservationRow;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaCollectionBatchRepository implements CollectionBatchRepository {
    private final CollectorObservationBatchRepository batches;
    private final CollectorVehicleObservationRepository observations;

    public JpaCollectionBatchRepository(CollectorObservationBatchRepository batches,
                                        CollectorVehicleObservationRepository observations) {
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
    public long save(CollectionBatch batch) {
        if (batch.id() == null) {
            return batches.save(new ObservationBatchJpaEntity(batch)).getId();
        }
        batches.findById(batch.id()).orElseThrow().apply(batch);
        batches.flush();
        return batch.id();
    }

    @Override
    public void deleteObservationsOf(final long batchId) {
        observations.deleteByObservationBatchId(batchId);
    }

    @Override
    public List<StoredObservations.Row> saveObservations(CollectionBatch batch, List<UpstreamObservationRow> rows) {
        return observations.saveAll(rows.stream()
                .map(row -> new VehicleObservationJpaEntity(batch.id(), batch.routeVersionId(), row)).toList())
            .stream().map(VehicleObservationJpaEntity::storedRow).toList();
    }
}
