package com.gustler.backend.processor;

public record VehicleStopTarget(
    ObservedVehicle observation,
    ForecastDistance distance
) {

    public VehicleStopTarget {
        if (!observation.hasKnownSeats()) {
            throw new IllegalArgumentException("잔여석을 모르는 관측으로는 예보하지 않는다: " + observation);
        }
    }

    public int stopOrderToForecast() {
        return observation.stopOrder() + distance.stopCount();
    }

    public int remainingSeats() {
        return observation.remainingSeats();
    }
}
