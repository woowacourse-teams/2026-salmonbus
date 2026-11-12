package com.gustler.backend.api.salmon.offline;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 차량 한 대가 정류장 하나를 떠난 뒤의 좌석을 차량 관측에서 찾는다. 두 좌석 예보 계약이 정답으로 보는 관측을 둘 다 낸다.
 * <ul>
 *   <li>first: 통과 순번이 그 정류장 순번과 같은 첫 관측. FIRST_POST_STOP_ZERO 예보의 정산(ArrivalLabelResolver)이 고르는
 *       관측이다.</li>
 *   <li>last: 통과 순번이 그 정류장 순번 이상이고 다음 승차 정류장 순번 미만인 관측 가운데 마지막. 다음 승차 정류장을 지난
 *       관측이 있어야 확정한다. DEPARTURE_ZERO 예보의 정답(LesPassEvents의 출발 관측)과 같은 관측이다.</li>
 * </ul>
 * 추적을 끊는 조건은 ArrivalLabelResolver와 같다. 연속 관측 간격이 90초를 넘거나, 통과 순번이 되돌아가거나, 운행 방향이
 * 출발점의 방향과 다르거나, 운행 방향이 없는 관측을 만나면 끊는다. 편도(trip) 연결 규칙은 쓰지 않는다.
 *
 * <p>last는 DEPARTURE_ZERO 정답의 근사다. 제품(LesPassEvents)은 모델 번들의 정차 순번으로 다음 정류장을 정하고 작은 순번
 * 후퇴를 허용하는데, 여기서는 route_stop의 승차 가능 정류장을 쓰고 순번이 되돌아가면 끊는다.
 */
record PassOutcome(Seats first, Seats last) {
    static final Duration LONGEST_GAP = Duration.ofSeconds(90);

    enum Unknown { NO_VEHICLE_ID, NO_VISIT, NO_OBSERVATION, ALREADY_PASSED, NOT_REACHED, SKIPPED, LOST, SEAT_MISSING,
        NOT_CONFIRMED }

    /** 좌석 수를 알면 seats에, 모르면 그 이유를 unknown에 담는다. */
    record Seats(OptionalInt seats, Optional<Unknown> unknown) {
        static Seats of(Integer seats) {
            return seats == null ? unknown(Unknown.SEAT_MISSING)
                : new Seats(OptionalInt.of(seats), Optional.empty());
        }

        static Seats unknown(Unknown reason) {
            return new Seats(OptionalInt.empty(), Optional.of(reason));
        }

        boolean known() {
            return seats.isPresent();
        }

        boolean full() {
            return seats.isPresent() && seats.getAsInt() == 0;
        }

        String label() {
            return unknown.map(reason -> "UNKNOWN/" + reason).orElseGet(() -> full() ? "FULL" : "SEATS_AVAILABLE");
        }
    }

    /** direction은 운행 방향이다. operational_observation.quality_direction은 0 또는 1이라 비지 않는다. */
    record Observation(Instant at, int passedStopOrder, Integer remainingSeats, Long direction) {
    }

    static PassOutcome unknown(Unknown reason) {
        return new PassOutcome(Seats.unknown(reason), Seats.unknown(reason));
    }

    /**
     * @param start 기준 시각까지의 그 차량의 마지막 관측. 없으면 빈 값
     * @param later 기준 시각 뒤의 그 차량 관측. 시각 오름차순
     * @param nextBoardingStop 그 정류장 다음의 승차 정류장 순번. 없으면 빈 값이고 last는 확정하지 못한다
     * @param notBefore 이 시각까지의 뒤 관측은 건너뛴다. 제품 정산이 예보 계산 시각 이전의 관측을 쓰지 않는 것과 같다
     */
    static PassOutcome resolve(Optional<Observation> start, List<Observation> later, int targetStopOrder,
                               OptionalInt nextBoardingStop, Optional<Instant> notBefore) {
        if (start.isEmpty() && later.isEmpty()) {
            return unknown(Unknown.NO_OBSERVATION);
        }
        if (start.isPresent() && start.get().passedStopOrder() >= targetStopOrder) {
            return unknown(Unknown.ALREADY_PASSED);
        }
        Observation previous = start.orElse(null);
        // 출발점의 방향이 없으면 방향을 보지 않는다. 출발점 관측이 없으면 첫 뒤 관측의 방향을 기준으로 둔다.
        Long direction = start.isPresent() ? start.get().direction() : later.get(0).direction();
        Observation first = null;
        Observation last = null;
        boolean confirmed = false;
        Unknown stopped = Unknown.NOT_REACHED;
        for (Observation observation : later) {
            if (notBefore.isPresent() && !observation.at().isAfter(notBefore.get())) {
                continue;
            }
            if (direction != null && !direction.equals(observation.direction())
                || previous != null && breaks(previous, observation)) {
                stopped = Unknown.LOST;
                break;
            }
            int passed = observation.passedStopOrder();
            if (first == null && passed > targetStopOrder) {
                stopped = Unknown.SKIPPED;
                break;
            }
            if (first != null && (nextBoardingStop.isEmpty() || passed >= nextBoardingStop.getAsInt())) {
                confirmed = nextBoardingStop.isPresent();
                break;
            }
            if (passed >= targetStopOrder) {
                first = first == null ? observation : first;
                last = observation;
            }
            previous = observation;
        }
        if (first == null) {
            return unknown(stopped);
        }
        return new PassOutcome(Seats.of(first.remainingSeats()),
            confirmed ? Seats.of(last.remainingSeats()) : Seats.unknown(Unknown.NOT_CONFIRMED));
    }

    static boolean breaks(Observation previous, Observation observation) {
        return Duration.between(previous.at(), observation.at()).compareTo(LONGEST_GAP) > 0
            || observation.passedStopOrder() < previous.passedStopOrder();
    }
}
