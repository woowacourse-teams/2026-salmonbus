package com.gustler.backend.api.board.domain;

public record ApproachingVehicle(
    String vehicleId,
    int horizonStops,
    double seatAvailableProbability,
    Double expectedSeats
) {
}
