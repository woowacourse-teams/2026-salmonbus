package com.gustler.backend.api.vehicle.domain;

/**
 * 유효한 차량 관측·정상적인 차량 부재·현재 확인 불가를 구분한다.
 * 실패나 결측을 0대 관측으로 바꾸지 않는다.
 */
public enum VehicleObservationState {
    VEHICLES_PRESENT,
    NO_VEHICLES_OBSERVED,
    UNKNOWN,
    ;
}
