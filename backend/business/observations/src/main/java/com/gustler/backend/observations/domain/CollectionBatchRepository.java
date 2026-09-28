package com.gustler.backend.observations.domain;

import java.util.List;
import java.util.Optional;

/** 수집 배치와 해당 시도의 관측을 함께 저장한다. */
public interface CollectionBatchRepository {
    Optional<CollectionBatch> findByPlan(CollectionPlan plan);
    CollectionBatch getById(long batchId);
    long save(CollectionBatch batch);
    void deleteObservationsOf(long batchId);
    List<StoredObservations.Row> saveObservations(CollectionBatch batch, List<UpstreamObservationRow> rows);
}
