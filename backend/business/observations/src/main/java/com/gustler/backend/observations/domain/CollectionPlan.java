package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/** 수집 계획. 같은 계획의 재시도에서는 attemptKey를 유지한다. */
public record CollectionPlan(long routeVersionId, OffsetDateTime scheduledAt, String attemptKey) {
    public CollectionPlan {
        if (routeVersionId <= 0) {
            throw new IllegalArgumentException("노선 버전 ID는 양수여야 한다");
        }
        Objects.requireNonNull(scheduledAt);
        if (attemptKey == null || attemptKey.isBlank()) {
            throw new IllegalArgumentException("수집 계획을 구별하는 키가 필요하다");
        }
    }
}
