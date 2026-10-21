package com.gustler.backend.api.vehicle.domain;

import java.util.Objects;

/**
 * 조회할 차량 관측과 좌석·진행 상태를 전달한다.
 * 미제공 수치를 임의의 0으로 채우지 않는다.
 */
public record ObservedVehicle(
    String vehicleId,
    VehicleDirection direction,
    int currentStopSequence,
    String stopId,
    String stopName,
    VehiclePhase phase,
    VehicleSeat seat
) {
    public ObservedVehicle {
        Objects.requireNonNull(direction, "direction은 null일 수 없습니다.");
        if (currentStopSequence < 1) {
            throw new IllegalArgumentException("currentStopSequence는 1 이상이어야 합니다.");
        }
        Objects.requireNonNull(stopId, "stopId는 null일 수 없습니다.");
        Objects.requireNonNull(stopName, "stopName은 null일 수 없습니다.");
        Objects.requireNonNull(phase, "phase는 null일 수 없습니다.");
        Objects.requireNonNull(seat, "seat는 null일 수 없습니다.");
    }
}
