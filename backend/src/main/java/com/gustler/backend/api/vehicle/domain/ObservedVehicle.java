package com.gustler.backend.api.vehicle.domain;

import java.util.Objects;

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
