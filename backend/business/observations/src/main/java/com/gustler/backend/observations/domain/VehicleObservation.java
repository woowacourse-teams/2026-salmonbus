package com.gustler.backend.observations.domain;

import java.util.Objects;

/**
 * 정규화한 차량 관측이다.
 * 정류장, 운행 상태, 좌석의 알려진 값과 미제공 의미를 구분한다.
 */
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

    /** 정원 근거로 조사할 수 있는 양수 좌석인지 확인한다. 0석과 미관측은 제외한다. */
    public boolean hasPositiveSeats() {
        return remainingSeats instanceof RemainingSeats.Known known && known.seats() > 0;
    }

    /**
     * GBIS에서 받은 운행 상태가 해석 가능한 값인지 확인한다.
     * 0 이동 중 ,  1 도착 중 ,  2 지나감만 해석하며, 그 밖의 값은 상태를 알 수 없다.
     */
    public boolean hasKnownRunningState() {
        if (runningState == null) {
            return false;
        }
        return runningState == MOVING_BETWEEN_STOPS
            || runningState == ARRIVED_AT_STOP
            || runningState == DEPARTED_FROM_STOP;
    }

    /**
     * 필수값이 모두 있어 저장할 수 있는 관측인지 확인한다.
     *
     * <p>필수값이 하나라도 없으면 해당 행의 INSERT가 실패한다. 수집 배치와 관측은 같은 트랜잭션에
     * 저장되므로 차량 하나의 필수값이 빠져도 같은 배치의 다른 관측과 수집 완료 기록이 같이 롤백된다.
     */
    public boolean isStorable() {
        return hasKnownStop() && hasKnownRunningState();
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

    private static Integer crowdLevelOf(Integer crowdLevel) {
        if (crowdLevel == null || isOutsideCrowdLevelRange(crowdLevel)) {
            return null;
        }
        return crowdLevel;
    }

    private static boolean isOutsideCrowdLevelRange(final int crowdLevel) {
        return crowdLevel < LOWEST_CROWD_LEVEL || crowdLevel > HIGHEST_CROWD_LEVEL;
    }
}
