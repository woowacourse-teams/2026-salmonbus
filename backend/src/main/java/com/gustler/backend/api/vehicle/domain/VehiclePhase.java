package com.gustler.backend.api.vehicle.domain;

public enum VehiclePhase {

    ARRIVING,
    DEPARTED,
    IN_TRANSIT,
    ;

    public static VehiclePhase fromRunningState(Integer runningState) {
        if (runningState == null) {
            throw new IllegalArgumentException("runningState는 null일 수 없습니다.");
        }

        return switch (runningState) {
            case 0 -> IN_TRANSIT;
            case 1 -> ARRIVING;
            case 2 -> DEPARTED;
            default -> throw new IllegalArgumentException(
                "지원하지 않는 runningState입니다: " + runningState
            );
        };
    }
}
