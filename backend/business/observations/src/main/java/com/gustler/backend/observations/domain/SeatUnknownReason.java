package com.gustler.backend.observations.domain;

/**
 * 좌석 수를 제공하지 못한 이유다.
 * 알려진 0석과 알 수 없는 값을 구분한다.
 */
public enum SeatUnknownReason {
    REPORTED_UNKNOWN,
    NOT_REPORTED,
    ;
}
