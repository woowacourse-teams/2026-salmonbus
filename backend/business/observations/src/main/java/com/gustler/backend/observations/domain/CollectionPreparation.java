package com.gustler.backend.observations.domain;

/** 호출 한도를 예약한 결과와 수집 배치 ID를 반환한다. */
public record CollectionPreparation(long batchId, boolean reserved, String keyAlias) {

    public CollectionPreparation {
        if (reserved == (keyAlias == null)) {
            throw new IllegalArgumentException("예약한 수집에만 호출 키가 있다");
        }
    }

    /** 배치 ID와 기존 배치의 재시도 여부만 반환한다. 저장된 배치 상태는 복사하지 않는다. */
    public record Batch(long batchId, boolean restarted) {

    }
}
