package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.ApproachingVehicle;

public record ApproachingVehicleResponse(
    String vehicleId,
    int horizonStops,
    ForecastResponse forecast
) {

    static ApproachingVehicleResponse from(
        ApproachingVehicle vehicle
    ) {
        return new ApproachingVehicleResponse(
            vehicle.vehicleId(),
            vehicle.horizonStops(),
            ForecastResponse.from(vehicle.forecast())
        );
    }
}
