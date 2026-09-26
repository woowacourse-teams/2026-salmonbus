package com.gustler.backend.observations.domain;

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
        Objects.requireNonNull(remainingSeats, "잔여석 값이나 알 수 없는 사유가 필요하다");
        crowdLevel = crowdLevelOf(crowdLevel);
    }

    /**
     * 관측한 정류소를 식별할 수 있는지 확인한다.
     * 순번과 정류소 ID는 모두 필수값이므로 둘 다 있어야 저장할 수 있다.
     */
    public boolean hasKnownStop() {
        return stopSequence != null && stopId != null;
    }

    /**
     * 상류에서 받은 운행 상태가 해석 가능한 값인지 확인한다.
     * 0 이동 중 · 1 도착 중 · 2 지나감만 해석하며, 그 밖의 값은 상태를 알 수 없다.
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
