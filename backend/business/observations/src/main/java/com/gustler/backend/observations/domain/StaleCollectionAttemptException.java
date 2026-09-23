package com.gustler.backend.observations.domain;

public class StaleCollectionAttemptException extends RuntimeException {
    public StaleCollectionAttemptException(CollectionAttemptToken token, final int currentAttemptNumber) {
        super("현재 수집 시도와 일치하지 않는다: batch=%d, requested=%d, current=%d"
            .formatted(token.batchId(), token.attemptNumber(), currentAttemptNumber));
    }
}
