package com.gustler.backend.observations.application;

import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.CollectionBatchRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 배치가 예보 입력으로 확정되는 시점을 저장 경계에서 반영한다.
 */
@Component
@Transactional(readOnly = false, propagation = Propagation.MANDATORY)
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
