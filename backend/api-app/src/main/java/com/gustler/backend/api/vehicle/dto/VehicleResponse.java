package com.gustler.backend.api.vehicle.dto;

import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import com.gustler.backend.api.vehicle.domain.VehicleDirection;
import com.gustler.backend.api.vehicle.domain.VehiclePhase;
import java.util.Objects;

public record VehicleResponse(
    String vehicleId,
    VehicleDirection direction,
    int currentStopSequence,
    String stopId,
    String stopName,
    VehiclePhase phase,
    SeatResponse seat
) {

    public VehicleResponse {
        Objects.requireNonNull(direction, "direction은 null일 수 없습니다.");
        Objects.requireNonNull(stopId, "stopId는 null일 수 없습니다.");
        Objects.requireNonNull(stopName, "stopName은 null일 수 없습니다.");
        Objects.requireNonNull(phase, "phase는 null일 수 없습니다.");
        Objects.requireNonNull(seat, "seat는 null일 수 없습니다.");
    }

    public static VehicleResponse from(ObservedVehicle vehicle) {
        return new VehicleResponse(
            vehicle.vehicleId(),
            vehicle.direction(),
            vehicle.currentStopSequence(),
            vehicle.stopId(),
            vehicle.stopName(),
            vehicle.phase(),
            SeatResponse.from(vehicle.seat())
        );
    }
}
