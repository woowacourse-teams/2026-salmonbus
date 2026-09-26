package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 수집 배치와 해당 시도의 관측을 함께 저장한다. */
public interface ObservationRepository {
    void lockPlan(CollectionPlan plan);
    CollectionAttemptToken openReserved(CollectionPlan plan);
    CollectionAttemptToken openNotReserved(CollectionPlan plan);
    boolean isAwaitingDispatch(CollectionAttemptToken token);
    void markDispatching(CollectionAttemptToken token, OffsetDateTime requestedAt);
    void abandonBeforeSend(CollectionAttemptToken token);
    void concludeWithoutRows(CollectionAttemptToken token, ObservationBatchConclusion conclusion,
                             OffsetDateTime responseReceivedAt);
    long routeVersionOf(long batchId);
    Optional<StoredObservations> concludeWithRows(CollectionAttemptToken token, ObservationBatchConclusion conclusion,
                          CollectedObservations collected, OffsetDateTime responseReceivedAt);
}
