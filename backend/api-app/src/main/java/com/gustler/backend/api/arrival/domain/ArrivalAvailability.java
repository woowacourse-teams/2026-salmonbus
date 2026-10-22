package com.gustler.backend.api.arrival.domain;

/**
 * 도착 예측의 제공 가능 상태다.
 * 예측 부재와 미수집·만료로 인한 제공 불가를 구분한다.
 */
public enum ArrivalAvailability {
    READY, NO_PREDICTION, UNAVAILABLE
}
