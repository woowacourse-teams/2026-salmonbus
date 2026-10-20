package com.gustler.backend.forecasting.domain.quality;

import com.gustler.backend.forecasting.domain.model.SeatGrid;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 연속 관측의 출발·방향 전환·단절로 편도 경계를 판정한다.
 * 좌석 범위 위반은 경계 종류와 관계없이 마지막에 제외 상태로 반영한다.
 */
public final class OneWayTripClassifier {
    public static final String RULE_VERSION = "one-way-seat-range-v3";
    public static final Duration DEFAULT_MAXIMUM_GAP = Duration.ofMinutes(10);

    public enum Status {
        ELIGIBLE, BOUNDARY_UNCONFIRMED, EXCLUDED
    }
    public enum Boundary {
        DEPARTURE, DIRECTION_CHANGE, CONTINUATION, UNCONFIRMED
    }
    public record Observation(long id, String vehicleId, Instant at, int stopOrder,
        Integer runningState, Integer seats) {
    }
    public record Route(int firstStop, int lastStop, Integer turnStop, Duration maximumGap) {
        public Route {
            maximumGap = Objects.requireNonNullElse(maximumGap, DEFAULT_MAXIMUM_GAP);
            if (firstStop >= lastStop || maximumGap.isZero() || maximumGap.isNegative()) {
                throw new IllegalArgumentException("정류장 범위와 관측 연결 간격을 확인해야 한다");
            }
        }
    }
    public record Start(int stopOrder, Integer runningState) {
    }
    public record Previous(Observation observation, long tripId, Status status, Start start) {
        public Previous(Observation observation, final long tripId, Status status) {
            this(observation, tripId, status, new Start(observation.stopOrder(), observation.runningState()));
        }
    }
    public record Decision(long tripId, Status status, Boundary boundary) {
    }

    private OneWayTripClassifier() {
    }

    /**
     * 이전 관측과 현재 관측으로 편도 운행 적격성을 판정한다. 좌석 범위가 잘못된 관측은 경계 판정 뒤에도 제외한다.
     */
    public static Decision classify(Route route, Previous previous, Observation current) {
        final boolean comparable = previous != null && current.vehicleId() != null
            && Objects.equals(previous.observation().vehicleId(), current.vehicleId())
            && !current.at().isBefore(previous.observation().at());
        final boolean connected = comparable
            && Duration.between(previous.observation().at(), current.at()).compareTo(route.maximumGap()) <= 0;
        final boolean departure = current.vehicleId() != null && isDeparture(route, current);
        final boolean repeatedDeparture = connected && departure
            && previous.observation().stopOrder() == current.stopOrder()
            && direction(route, previous.start()
                .stopOrder(), previous.start()
                .runningState()) == direction(route, current);
        final long tripId = current.id();
        if (departure && !repeatedDeparture) {
            return withSeatQuality(current, tripId, Status.ELIGIBLE, Boundary.DEPARTURE);
        }
        if (!comparable) {
            return withSeatQuality(current, tripId, Status.BOUNDARY_UNCONFIRMED, Boundary.UNCONFIRMED);
        }
        final int before = direction(route, previous.start().stopOrder(), previous.start().runningState());
        int after = direction(route, current);
        // 같은 정류장에서 운행 상태만 반복되어도 편도 방향을 되돌리지 않는다.
        if (current.stopOrder() == previous.observation().stopOrder()) {
            after = before;
        }
        final boolean forward = current.stopOrder() >= previous.observation().stopOrder();
        final boolean wrap = before == 1 && after == 0 && !forward;
        if (before != after && (forward || wrap)) {
            return withSeatQuality(current, tripId, Status.ELIGIBLE, Boundary.DIRECTION_CHANGE);
        }
        if (connected && before == after && (forward || (before == 1
            && current.stopOrder() == route.firstStop() && !departure))) {
            return withSeatQuality(current, previous.tripId(), previous.status(), Boundary.CONTINUATION);
        }
        return withSeatQuality(current, tripId, Status.BOUNDARY_UNCONFIRMED, Boundary.UNCONFIRMED);
    }

    /** 경계 판정 경로와 관계없이 좌석 범위 위반은 마지막에 동일하게 제외한다. */
    private static Decision withSeatQuality(
        Observation current,
        final long tripId,
        Status status,
        Boundary boundary
    ) {
        if (current.seats() != null && current.seats() > SeatGrid.LARGEST_SEATS) {
            return new Decision(tripId, Status.EXCLUDED, boundary);
        }
        return new Decision(tripId, status, boundary);
    }

    static boolean isDeparture(Route route, Observation observation) {
        return Integer.valueOf(2).equals(observation.runningState())
            && (observation.stopOrder() == route.firstStop()
                || Objects.equals(observation.stopOrder(), route.turnStop()));
    }

    static int direction(Route route, Observation observation) {
        return direction(route, observation.stopOrder(), observation.runningState());
    }

    private static int direction(Route route, final int stopOrder, Integer state) {
        if (route.turnStop() == null) {
            return 0;
        }
        if (stopOrder == route.firstStop()
            && !Integer.valueOf(2).equals(state)) {
            return 1;
        }
        if (stopOrder > route.turnStop()
            || (stopOrder == route.turnStop() && Integer.valueOf(2).equals(state))) {
            return 1;
        }
        return 0;
    }
}
