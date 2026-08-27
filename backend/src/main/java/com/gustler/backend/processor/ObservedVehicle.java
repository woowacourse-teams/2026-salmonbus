package com.gustler.backend.processor;

import java.time.Instant;

public record ObservedVehicle(
    String vehicleId,
    int passedStopOrder,
    Instant observedAt,
    Integer remainingSeats
) {

    public boolean hasKnownSeats() {
        return remainingSeats != null && remainingSeats >= 0;
    }
}
