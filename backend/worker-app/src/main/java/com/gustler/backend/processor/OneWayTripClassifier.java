package com.gustler.backend.processor;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 좌석 초기화를 추정하지 않는다. 관측을 연결할 근거와 모델 입력 사용 여부만 판정한다. */
public final class OneWayTripClassifier {
    public static final String RULE_VERSION = "one-way-seat-range-v1";

    public enum Status { ELIGIBLE, BOUNDARY_UNCONFIRMED, EXCLUDED }
    public enum Boundary { DEPARTURE, DIRECTION_CHANGE, CONTINUATION, UNCONFIRMED }
    public record Observation(long id, String vehicleId, Instant at, int stopOrder,
                              Integer runningState, Integer seats) { }
    public record Route(int firstStop, int lastStop, Integer turnStop, Duration maximumGap) {
        public Route {
            if (firstStop >= lastStop || (maximumGap != null && (maximumGap.isZero() || maximumGap.isNegative()))) {
                throw new IllegalArgumentException("정류장 범위와 관측 연결 간격을 확인해야 한다");
            }
        }
    }
    public record Start(int stopOrder, Integer runningState) { }
    public record Previous(Observation observation, long tripId, Status status, Start start) {
        public Previous(Observation observation, long tripId, Status status) {
            this(observation, tripId, status, new Start(observation.stopOrder(), observation.runningState()));
        }
    }
    public record Decision(long tripId, Status status, Boundary boundary) { }

    private OneWayTripClassifier() { }

    public static Decision classify(Route route, Previous previous, Observation current) {
        boolean comparable = previous != null && current.vehicleId() != null
            && Objects.equals(previous.observation().vehicleId(), current.vehicleId())
            && !current.at().isBefore(previous.observation().at());
        boolean connected = comparable
            // GBIS 순번/출발 상태로 연결하고, 검토된 시간 기준이 있을 때만 추가로 공백을 제한한다.
            && (route.maximumGap() == null
                || Duration.between(previous.observation().at(), current.at()).compareTo(route.maximumGap()) <= 0);
        boolean departure = current.vehicleId() != null && isDeparture(route, current);
        boolean repeatedDeparture = connected && departure
            && previous.observation().stopOrder() == current.stopOrder()
            && direction(route, previous.start().stopOrder(), previous.start().runningState()) == direction(route, current);
        long tripId = current.id();
        Status status = Status.BOUNDARY_UNCONFIRMED;
        Boundary boundary = Boundary.UNCONFIRMED;
        if (departure && !repeatedDeparture) {
            status = Status.ELIGIBLE;
            boundary = Boundary.DEPARTURE;
        } else if (comparable) {
            int before = direction(route, previous.start().stopOrder(), previous.start().runningState());
            int after = direction(route, current);
            // 같은 정류장에서 운행 상태만 반복되어도 편도 방향을 되돌리지 않는다.
            if (current.stopOrder() == previous.observation().stopOrder()) {
                after = before;
            }
            boolean forward = current.stopOrder() >= previous.observation().stopOrder();
            boolean wrap = before == 1 && after == 0 && !forward;
            if (before != after && (forward || wrap)) {
                status = Status.ELIGIBLE;
                boundary = Boundary.DIRECTION_CHANGE;
            } else if (connected && before == after && (forward || (before == 1
                && current.stopOrder() == route.firstStop() && !departure))) {
                tripId = previous.tripId();
                status = previous.status();
                boundary = Boundary.CONTINUATION;
            }
        }
        if (current.seats() != null && current.seats() > 70) {
            status = Status.EXCLUDED;
        }
        return new Decision(tripId, status, boundary);
    }

    private static boolean isDeparture(Route route, Observation observation) {
        return Integer.valueOf(2).equals(observation.runningState())
            && (observation.stopOrder() == route.firstStop()
                || Objects.equals(observation.stopOrder(), route.turnStop()));
    }

    private static int direction(Route route, Observation observation) {
        return direction(route, observation.stopOrder(), observation.runningState());
    }

    private static int direction(Route route, int stopOrder, Integer state) {
        if (route.turnStop() == null) {
            return 0;
        }
        if (stopOrder == route.firstStop()
            && !Integer.valueOf(2).equals(state)) {
            return 1;
        }
        return stopOrder > route.turnStop()
            || (stopOrder == route.turnStop() && Integer.valueOf(2).equals(state))
            ? 1 : 0;
    }
}
