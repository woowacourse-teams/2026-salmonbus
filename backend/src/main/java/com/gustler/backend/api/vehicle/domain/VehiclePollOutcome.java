package com.gustler.backend.api.vehicle.domain;

public enum VehiclePollOutcome {

    SUCCESS_ROWS,
    SUCCESS_EMPTY,
    UNKNOWN;

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
