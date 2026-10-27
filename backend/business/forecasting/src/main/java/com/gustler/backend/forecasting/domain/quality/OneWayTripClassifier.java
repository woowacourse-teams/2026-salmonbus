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
    public static final String RULE_VERSION = "one-way-seat-range-v4";
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
            if (firstStop >= lastStop || isInvalidMaximumGap(maximumGap)) {
                throw new IllegalArgumentException("정류장 범위와 관측 연결 간격을 확인해야 한다");
            }
        }

        private static boolean isInvalidMaximumGap(Duration maximumGap) {
            return maximumGap.isZero() || maximumGap.isNegative();
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
        final boolean comparable = canCompareObservations(previous, current);
        final boolean connected = isWithinMaximumGap(route, previous, current, comparable);
        final boolean departure = current.vehicleId() != null && isDeparture(route, current);
        final boolean repeatedDeparture = isRepeatedDeparture(route, previous, current, connected, departure);
        final long tripId = current.id();
        if (departure && !repeatedDeparture) {
            return withSeatQuality(current, tripId, Status.ELIGIBLE, Boundary.DEPARTURE);
        }
        if (!comparable) {
            return withSeatQuality(current, tripId, Status.BOUNDARY_UNCONFIRMED, Boundary.UNCONFIRMED);
        }
        Start previousStart = previous.start();
        Observation previousObservation = previous.observation();
        final int before = direction(route, previousStart.stopOrder(), previousStart.runningState());
        final int after = directionAfterObservation(route, previous, current, before);
        final boolean forward = current.stopOrder() >= previousObservation.stopOrder();
        if (crossesDirectionBoundary(before, after, forward)) {
            return withSeatQuality(current, tripId, Status.ELIGIBLE, Boundary.DIRECTION_CHANGE);
        }
        if (continuesPreviousTrip(route, current, connected, departure, before, after, forward)) {
            return withSeatQuality(current, previous.tripId(), previous.status(), Boundary.CONTINUATION);
        }
        return withSeatQuality(current, tripId, Status.BOUNDARY_UNCONFIRMED, Boundary.UNCONFIRMED);
    }

    private static boolean canCompareObservations(Previous previous, Observation current) {
        if (previous == null || current.vehicleId() == null) {
            return false;
        }
        Observation previousObservation = previous.observation();
        if (!Objects.equals(previousObservation.vehicleId(), current.vehicleId())) {
            return false;
        }
        return !current.at().isBefore(previousObservation.at());
    }

    private static boolean isWithinMaximumGap(
        Route route,
        Previous previous,
        Observation current,
        final boolean comparable
    ) {
        if (!comparable) {
            return false;
        }
        Instant previousAt = previous.observation().at();
        Duration gap = Duration.between(previousAt, current.at());
        return gap.compareTo(route.maximumGap()) <= 0;
    }

    private static boolean isRepeatedDeparture(
        Route route,
        Previous previous,
        Observation current,
        final boolean connected,
        final boolean departure
    ) {
        if (!connected || !departure) {
            return false;
        }
        Observation previousObservation = previous.observation();
        if (previousObservation.stopOrder() != current.stopOrder()) {
            return false;
        }
        Start previousStart = previous.start();
        int previousDirection = direction(route, previousStart.stopOrder(), previousStart.runningState());
        return previousDirection == direction(route, current);
    }

    private static int directionAfterObservation(Route route, Previous previous, Observation current, final int before) {
        Observation previousObservation = previous.observation();
        if (current.stopOrder() == previousObservation.stopOrder()) {
            return before;
        }
        return direction(route, current);
    }

    private static boolean crossesDirectionBoundary(final int before, final int after, final boolean forward) {
        final boolean wrapsToOutboundDirection = before == 1 && after == 0 && !forward;
        return before != after && (forward || wrapsToOutboundDirection);
    }

    private static boolean continuesPreviousTrip(
        Route route,
        Observation current,
        final boolean connected,
        final boolean departure,
        final int before,
        final int after,
        final boolean forward
    ) {
        final boolean returnsToFirstStop = before == 1
            && current.stopOrder() == route.firstStop()
            && !departure;
        return connected && before == after && (forward || returnsToFirstStop);
    }

    /** 경계 판정 경로와 관계없이 좌석 범위 위반은 마지막에 동일하게 제외한다. */
    private static Decision withSeatQuality(
        Observation current,
        final long tripId,
        Status status,
        Boundary boundary
    ) {
        Integer seats = current.seats();
        if (seats != null && seats > SeatGrid.LARGEST_SEATS) {
            return new Decision(tripId, Status.EXCLUDED, boundary);
        }
        return new Decision(tripId, status, boundary);
    }

    /** 기점은 출발(2)과 교차로 통과(0)를, 회차지는 출발(2)만 편도 시작으로 본다. */
    static boolean isDeparture(Route route, Observation observation) {
        Integer state = observation.runningState();
        if (observation.stopOrder() == route.firstStop()) {
            return Integer.valueOf(2).equals(state) || Integer.valueOf(0).equals(state);
        }
        return Integer.valueOf(2).equals(state) && Objects.equals(observation.stopOrder(), route.turnStop());
    }

    static int direction(Route route, Observation observation) {
        return direction(route, observation.stopOrder(), observation.runningState());
    }

    private static int direction(Route route, final int stopOrder, Integer state) {
        if (route.turnStop() == null) {
            return 0;
        }
        if (stopOrder == route.firstStop()
            && Integer.valueOf(1).equals(state)) {
            return 1;
        }
        if (isInboundAtTurnStop(route, stopOrder, state)) {
            return 1;
        }
        return 0;
    }

    private static boolean isInboundAtTurnStop(Route route, int stopOrder, Integer state) {
        if (stopOrder > route.turnStop()) {
            return true;
        }
        return stopOrder == route.turnStop() && Integer.valueOf(2).equals(state);
    }
}
