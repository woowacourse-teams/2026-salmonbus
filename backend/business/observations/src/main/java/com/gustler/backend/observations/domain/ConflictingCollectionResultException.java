package com.gustler.backend.observations.domain;

public class ConflictingCollectionResultException extends RuntimeException {
    public ConflictingCollectionResultException(final long batchId) {
        super("이미 저장한 수집 결과와 다르다: batch=" + batchId);
    }
}
