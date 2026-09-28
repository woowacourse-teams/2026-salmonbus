package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.observations.domain.CollectionBatch;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaCollectionInputs implements CollectionInputs {
    private final CollectorObservationBatchRepository batches;

    public JpaCollectionInputs(CollectorObservationBatchRepository batches) {
        this.batches = batches;
    }

    @Override
    public void confirmInput(final long batchId, Instant confirmedAt) {
        ObservationBatchJpaEntity entity = batches.findById(batchId).orElseThrow();
        CollectionBatch batch = entity.toDomain();
        batch.confirmInput(confirmedAt.atOffset(ZoneOffset.UTC));
        entity.apply(batch);
        batches.flush();
    }
}
