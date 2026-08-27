package com.gustler.backend.processor;

public record VehicleStopTarget(
    ObservedVehicle observation,
    ForecastDistance distance
) {

    public VehicleStopTarget {
        if (!hasKnownSeats(observation)) {
            throw new IllegalArgumentException("잔여석을 모르는 관측으로는 예보하지 않는다: " + observation);
        }
    }

    public int targetStopOrder() {
        return observation.stopOrder() + distance.stopCount();
    }

    public int remainingSeats() {
        return observation.remainingSeats();
    }

    private static boolean hasKnownSeats(
        final ObservedVehicle observation
    ) {
        final Integer remainingSeats = observation.remainingSeats();
        return remainingSeats != null && remainingSeats >= 0;
    }
}
