package com.gustler.backend.processor;

public record VehicleStopTarget(
    ObservedVehicle observation,
    Horizon horizon
) {

    public VehicleStopTarget {
        if (observation.remainingSeats() == null) {
            throw new IllegalArgumentException("잔여석을 모르는 관측으로는 예보하지 않는다: " + observation);
        }
    }

    public int targetStopOrder() {
        return observation.stopOrder() + horizon.stopsAhead();
    }

    public int remainingSeats() {
        return observation.remainingSeats();
    }
}
