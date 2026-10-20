package com.gustler.backend.api.vehicle.domain;

/**
 * 저장된 수집 결과를 해석해 정상 수신과 실패를 구분한다.
 * 빈 정상 결과도 실패와 별도로 판단한다.
 */
public enum VehiclePollOutcome {
    SUCCESS_ROWS,
    SUCCESS_EMPTY,
    UNKNOWN,
    ;

    public static VehiclePollOutcome fromDatabaseValue(String value) {
        if (SUCCESS_ROWS.name().equals(value)) {
            return SUCCESS_ROWS;
        }
        if (SUCCESS_EMPTY.name().equals(value)) {
            return SUCCESS_EMPTY;
        }
        return UNKNOWN;
    }

    public boolean isNormal() {
        return this == SUCCESS_ROWS || this == SUCCESS_EMPTY;
    }
}
