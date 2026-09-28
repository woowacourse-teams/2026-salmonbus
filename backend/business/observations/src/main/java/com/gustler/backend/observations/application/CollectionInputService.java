package com.gustler.backend.observations.application;

import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.CollectionBatchRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class CollectionInputService implements CollectionInputs {
    private final CollectionBatchRepository batches;

    public CollectionInputService(CollectionBatchRepository batches) {
        this.batches = batches;
    }

    @Override
    public void confirmInput(final long batchId, Instant confirmedAt) {
        CollectionBatch batch = batches.getById(batchId);
        batch.confirmInput(confirmedAt.atOffset(ZoneOffset.UTC));
        batches.save(batch);
    }
}
