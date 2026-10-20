package com.gustler.backend.api.vehicle.domain;

import java.util.Optional;

/**
 * 원천 운행 상태를 API의 차량 진행 상태로 해석한다.
 * 알 수 없는 상태를 임의의 정상 상태로 채우지 않는다.
 */
public enum VehiclePhase {
    ARRIVING,
    DEPARTED,
    IN_TRANSIT,
    ;

    /**
     * vehicle_observation.running_state 는 nullable 이고 상류가 문서 밖 값을 줄 수도 있다.
     * 해석할 수 없으면 비어 있는 값을 돌려주고, 그 관측 행만 응답에서 빠진다.
     */
    public static Optional<VehiclePhase> fromRunningState(Integer runningState) {
        if (runningState == null) {
            return Optional.empty();
        }

        return switch (runningState) {
            case 0 -> Optional.of(IN_TRANSIT);
            case 1 -> Optional.of(ARRIVING);
            case 2 -> Optional.of(DEPARTED);
            default -> Optional.empty();
        };
    }
}
