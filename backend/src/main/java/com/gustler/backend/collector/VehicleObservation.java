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
    private static final int MOVING_BETWEEN_STOPS = 0;
    private static final int ARRIVED_AT_STOP = 1;
    private static final int DEPARTED_FROM_STOP = 2;

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

    /**
     * 이 관측이 어느 정류소의 것인지 아는가.
     * 순번과 정류소 ID 가 둘 다 있어야 쌓을 수 있다. 둘 다 저장할 때 비울 수 없는 값이다.
     */
    public boolean hasKnownStop() {
        return stopSequence != null && stopId != null;
    }

    /**
     * 상류가 준 운행 상태가 우리가 뜻을 아는 값인가.
     * 0 이동 중 · 1 도착 중 · 2 지나감 셋뿐이고, 그 밖의 값은 무슨 상황인지 모른다.
     */
    public boolean hasKnownRunningState() {
        if (runningState == null) {
            return false;
        }
        return runningState == MOVING_BETWEEN_STOPS
            || runningState == ARRIVED_AT_STOP
            || runningState == DEPARTED_FROM_STOP;
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
