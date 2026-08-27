package com.gustler.backend.api.board.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gustler.backend.api.board.domain.ApproachingVehicle;

public record ApproachingVehicleResponse(
    String vehicleId,
    int horizonStops,
    double seatAvailableProbability,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Double expectedSeats
) {

    static ApproachingVehicleResponse from(final ApproachingVehicle vehicle) {
        return new ApproachingVehicleResponse(
            vehicle.vehicleId(),
            vehicle.horizonStops(),
            vehicle.seatAvailableProbability(),
            vehicle.expectedSeats()
        );
    }
}
