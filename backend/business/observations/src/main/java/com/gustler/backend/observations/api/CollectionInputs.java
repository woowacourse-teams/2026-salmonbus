package com.gustler.backend.observations.api;

import java.time.Instant;

/**
 * 저장한 수집 배치의 예보 입력 확정 시점을 반영하는 업무 경계다.
 */
public interface CollectionInputs {
    /**
     * 첫 입력 확인 시각만 저장한다. 기존 확인 시각은 재발행에서도 유지한다. 호출자의 트랜잭션에 참여한다.
     */
    void confirmInput(long batchId, Instant confirmedAt);
}
