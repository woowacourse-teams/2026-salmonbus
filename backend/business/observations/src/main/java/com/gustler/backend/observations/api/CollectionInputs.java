package com.gustler.backend.observations.api;

import java.time.Instant;

/**
 * 저장한 수집 배치의 예보 입력 확정 시점을 반영하는 업무 경계다.
 */
public interface CollectionInputs {
    /**
     * 관측 묶음이 예측 입력으로 확정된 시각을 저장한다. 예측 발행과 같은 트랜잭션에서 호출한다.
     */
    void confirmInput(long batchId, Instant confirmedAt);
}
