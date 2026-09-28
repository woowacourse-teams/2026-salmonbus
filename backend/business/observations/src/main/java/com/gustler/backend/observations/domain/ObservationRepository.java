package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;

/** 수집 배치와 해당 시도의 관측을 함께 저장한다. */
public interface ObservationRepository {
    long openReserved(CollectionPlan plan);
    long openNotReserved(CollectionPlan plan);
    void markDispatching(long batchId, OffsetDateTime requestedAt);
    void abandonBeforeSend(long batchId);
    void concludeWithoutRows(long batchId, ObservationBatchConclusion conclusion, OffsetDateTime responseReceivedAt);
    StoredObservations concludeWithRows(long batchId, ObservationBatchConclusion conclusion,
                                        CollectedObservations collected, OffsetDateTime responseReceivedAt);
}
