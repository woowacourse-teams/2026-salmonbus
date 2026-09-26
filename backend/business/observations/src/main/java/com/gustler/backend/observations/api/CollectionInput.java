package com.gustler.backend.observations.api;

import java.time.Instant;

/** 잠근 수집 시도의 입력 상태. 호출자의 트랜잭션이 끝날 때까지 원 관측을 교체할 수 없다. */
public record CollectionInput(long batchId, long routeVersionId, int attemptNumber,
                              Instant observedAt, boolean successful, boolean inputConfirmed) {
}
