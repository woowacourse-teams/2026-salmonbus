package com.gustler.backend.processor;

public record VehicleStopTarget(
    ObservedVehicle observation,
    RouteStop targetStop
) {

    public VehicleStopTarget {
        if (!observation.hasKnownSeats()) {
            throw new IllegalArgumentException("잔여석을 모르는 관측으로는 예보하지 않는다: " + observation);
        }
        distanceBetween(observation, targetStop);
    }

    public ForecastDistance distance() {
        return distanceBetween(observation, targetStop);
    }

    public int stopOrder() {
        return targetStop.stopOrder();
    }

    public int remainingSeats() {
        return observation.remainingSeats();
    }

    private static ForecastDistance distanceBetween(
        final ObservedVehicle observation,
        final RouteStop targetStop
    ) {
        return new ForecastDistance(targetStop.stopOrder() - observation.stopOrder());
    }
}
