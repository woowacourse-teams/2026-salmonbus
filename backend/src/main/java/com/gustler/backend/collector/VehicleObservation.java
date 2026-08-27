package com.gustler.backend.collector;

import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import java.util.Objects;

public record VehicleObservation(
    String vehicleId,
    String plateNumber,
    Integer stopOrder,
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

    public VehicleObservation {
        Objects.requireNonNull(remainingSeats, "잔여석은 아는 것이든 모르는 것이든 있어야 한다");
    }

    public static VehicleObservation from(
        final BusLocation bus
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

    private static Integer crowdLevelOf(
        final Integer crowdLevel
    ) {
        if (crowdLevel == null || crowdLevel < LOWEST_CROWD_LEVEL || crowdLevel > HIGHEST_CROWD_LEVEL) {
            return null;
        }
        return crowdLevel;
    }
}
