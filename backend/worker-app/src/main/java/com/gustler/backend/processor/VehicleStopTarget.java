package com.gustler.backend.processor;

public record VehicleStopTarget(
    ObservedVehicle observation,
    RouteStop targetStop
) {

    public VehicleStopTarget {
        if (!observation.hasKnownSeats()) {
            throw new IllegalArgumentException("잔여석을 모르는 관측으로는 예보하지 않는다: " + observation);
        }
        if (!targetStop.boardingAllowed()) {
            throw new IllegalArgumentException("승차할 수 없는 정류장에는 예보하지 않는다: " + targetStop);
        }
        if (observation.routeVersionId() != targetStop.routeVersionId()) {
            throw new IllegalArgumentException(
                "관측과 대상 정류장의 노선 판본이 다르다: %d, %d"
                    .formatted(observation.routeVersionId(), targetStop.routeVersionId())
            );
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
        ObservedVehicle observation,
        RouteStop targetStop
    ) {
        return new ForecastDistance(targetStop.stopOrder() - observation.passedStopOrder());
    }
}
