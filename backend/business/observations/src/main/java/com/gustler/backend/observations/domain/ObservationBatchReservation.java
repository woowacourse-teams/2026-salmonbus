package com.gustler.backend.observations.domain;

/** 한도 예약의 성공 여부와 이번 수집 배치를 함께 반환한다. */
public record ObservationBatchReservation(long batchId, boolean reserved, String keyAlias) {
    public ObservationBatchReservation {
        if (reserved == (keyAlias == null)) {
            throw new IllegalArgumentException("예약한 수집에만 호출 키가 있다");
        }
    }
}
