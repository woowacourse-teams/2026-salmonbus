package com.gustler.backend.observations.domain;

/** 한도 예약의 성공 여부와 이번 수집 시도를 함께 반환한다. */
public record ObservationBatchReservation(CollectionAttemptToken token, boolean reserved, String keyAlias) {
    public ObservationBatchReservation {
        if (reserved == (keyAlias == null)) {
            throw new IllegalArgumentException("예약한 수집에만 호출 키가 있다");
        }
    }

    public long batchId() {
        return token.batchId();
    }
}
