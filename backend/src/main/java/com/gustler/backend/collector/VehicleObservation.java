package com.gustler.backend.collector;

import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import java.util.Objects;

public record VehicleObservation(
    String vehicleId,
    String plateNumber,
    Integer stopSequence,
    String stopId,
    Integer runningState,
    RemainingSeats remainingSeats,
    Integer crowdLevel,
    Integer vehicleType,
    Integer routeType,
    Integer tagless
) {

    private static final int LOWEST_CROWD_LEVEL = 1;
    private static final int HIGHEST_CROWD_LEVEL = 4;
    private static final int ARRIVED_AT_STOP = 1;

    public VehicleObservation {
        Objects.requireNonNull(remainingSeats, "잔여석은 아는 것이든 모르는 것이든 있어야 한다");
    }

    public static VehicleObservation from(
        BusLocation bus
    ) {
        return new VehicleObservation(
            bus.vehicleId(),
            bus.plateNumber(),
            bus.stopSequence(),
            bus.stopId(),
            bus.runningStatus(),
            RemainingSeats.from(bus.remainingSeatCount()),
            crowdLevelOf(bus.crowdLevel()),
            bus.vehicleType(),
            bus.routeType(),
            bus.taglessCode());
    }

    /** 이 버스가 지나온 정류소의 순번. 도착 중이면 그 정류소는 아직 안 지났다. */
    public Integer passedStopOrder() {
        if (stopSequence == null || runningState == null) {
            return null;
        }
        if (runningState == ARRIVED_AT_STOP) {
            return stopSequence - 1;
        }
        return stopSequence;
    }

    private static Integer crowdLevelOf(
        Integer crowdLevel
    ) {
        if (crowdLevel == null || crowdLevel < LOWEST_CROWD_LEVEL || crowdLevel > HIGHEST_CROWD_LEVEL) {
            return null;
        }
        return crowdLevel;
    }
}
