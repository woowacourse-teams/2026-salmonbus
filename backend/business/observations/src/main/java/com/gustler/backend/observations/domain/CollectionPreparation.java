package com.gustler.backend.observations.domain;

/** 한도 예약의 성공 여부와 이번 수집 배치를 함께 반환한다. */
public record CollectionPreparation(long batchId, boolean reserved, String keyAlias) {

    public CollectionPreparation {
        if (reserved == (keyAlias == null)) {
            throw new IllegalArgumentException("예약한 수집에만 호출 키가 있다");
        }
    }

    /** 준비한 번호와 기존 배치를 다시 열었는지만 반환한다. 배치의 저장 값은 복사하지 않는다. */
    public record Batch(long batchId, boolean restarted) {

    }
}
