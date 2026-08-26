package com.gustler.backend.processor;

import java.time.Instant;

public record ObservedVehicle(
    String vehicleId,
    int stopOrder,
    Instant observedAt,
    Integer remainingSeats
) {
}
