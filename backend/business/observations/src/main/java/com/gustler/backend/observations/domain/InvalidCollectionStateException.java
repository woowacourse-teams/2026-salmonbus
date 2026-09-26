package com.gustler.backend.observations.domain;

public class InvalidCollectionStateException extends RuntimeException {
    public InvalidCollectionStateException(final long batchId, ObservationBatchOutcome state, String action) {
        super("현재 수집 상태에서 수행할 수 없다: batch=%d, state=%s, action=%s"
            .formatted(batchId, state, action));
    }
}
