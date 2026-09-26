package com.gustler.backend.observations.domain;

/** 같은 배치를 다시 수집해도 이전 응답과 현재 시도를 구별한다. */
public record CollectionAttemptToken(long batchId, int attemptNumber) {
    public CollectionAttemptToken {
        if (batchId <= 0 || attemptNumber <= 0) {
            throw new IllegalArgumentException("수집 배치 ID와 시도 횟수는 양수여야 한다");
        }
    }
}
