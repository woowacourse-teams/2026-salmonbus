package com.gustler.backend.forecasting.domain.model.les;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** passes3의 30분 편도 / 90초 관측 연결 조건. 미래 관측 없는 열린 구간은 제외한다. */
public final class LesPassEvents {
    private LesPassEvents() {
    }

    public static List<LesPassEvent> resolve(List<LesObservation> observations, List<Integer> realStopOrders) {
        List<Integer> stops = realStopOrders.stream().distinct().sorted().toList();
        Map<String, LesPassEvent> eventsByTripStop = new LinkedHashMap<>();
        List<LesObservation> chain = new ArrayList<>();
        LesObservation previous = null;
        long trip = 0;
        for (LesObservation row : orderedIdentifiedObservations(observations)) {
            Duration gap = observationGap(previous, row);
            if (startsChain(previous, row, gap)) {
                addChain(chain, trip, stops, eventsByTripStop);
                chain.clear();
            }
            if (startsTrip(previous, row, gap)) {
                trip = row.observationId();
            }
            chain.add(row);
            previous = row;
        }
        addChain(chain, trip, stops, eventsByTripStop);
        return eventsByTripStop.values().stream()
            .sorted(Comparator.comparing(LesPassEvent::confirmedAt)
                .thenComparingLong(event -> event.departure().observationId()))
            .toList();
    }

    private static List<LesObservation> orderedIdentifiedObservations(List<LesObservation> observations) {
        return observations.stream()
            .filter(LesObservation::hasVehicleIdentity)
            .sorted(Comparator.comparing(LesObservation::vehicleId)
                .thenComparing(LesObservation::receivedAt)
                .thenComparingLong(LesObservation::observationId))
            .toList();
    }

    private static Duration observationGap(LesObservation previous, LesObservation current) {
        if (previous == null) {
            return Duration.ZERO;
        }
        return Duration.between(previous.receivedAt(), current.receivedAt());
    }

    private static boolean startsChain(LesObservation previous, LesObservation current, Duration gap) {
        return changedVehicle(previous, current)
            || gap.compareTo(Duration.ofSeconds(90)) > 0
            || movedBackwards(previous, current);
    }

    private static boolean startsTrip(LesObservation previous, LesObservation current, Duration gap) {
        return changedVehicle(previous, current)
            || gap.compareTo(Duration.ofMinutes(30)) > 0
            || movedBackwards(previous, current);
    }

    private static boolean changedVehicle(LesObservation previous, LesObservation current) {
        return previous == null || !previous.vehicleId().equals(current.vehicleId());
    }

    private static boolean movedBackwards(LesObservation previous, LesObservation current) {
        return previous != null && current.passedStopOrder() < previous.passedStopOrder() - 5;
    }

    private static void addChain(
        List<LesObservation> chain,
        final long trip,
        List<Integer> stops,
        Map<String, LesPassEvent> eventsByTripStop) {
        if (chain.size() < 3) {
            return;
        }
        for (int index = 0; index + 1 < stops.size(); index++) {
            final int target = stops.get(index);
            final int before = previousStop(stops, index);
            final int next = stops.get(index + 1);
            LesPassEvent event = resolvePass(chain, trip, target, before, next);
            if (event != null) {
                eventsByTripStop.put(event.vehicleId() + ":" + trip + ":" + target, event);
            }
        }
    }

    private static int previousStop(List<Integer> stops, final int index) {
        if (index == 0) {
            return 0;
        }
        return stops.get(index - 1);
    }

    /** 작은 순번 후퇴는 허용하되, 마지막 도착 전 관측보다 앞선 출발 관측을 연결하지 않는다. */
    private static LesPassEvent resolvePass(
        List<LesObservation> chain,
        final long trip,
        final int target,
        final int before,
        final int next) {
        LesObservation arrival = null;
        LesObservation first = null;
        LesObservation last = null;
        for (LesObservation row : chain) {
            if (isBeforeTarget(row, before, target)) {
                arrival = row;
                first = null;
                last = null;
                continue;
            }
            if (isAfterTarget(arrival, row, target, next)) {
                if (first == null) {
                    first = row;
                }
                last = row;
                continue;
            }
            if (last != null && row.passedStopOrder() >= next) {
                return confirmedPass(arrival, first, last, row, trip, target);
            }
        }
        return null;
    }

    private static boolean isBeforeTarget(LesObservation row, final int before, final int target) {
        return row.passedStopOrder() >= before && row.passedStopOrder() < target;
    }

    private static boolean isAfterTarget(
        LesObservation arrival,
        LesObservation row,
        final int target,
        final int next) {
        return arrival != null && row.passedStopOrder() >= target && row.passedStopOrder() < next;
    }

    private static LesPassEvent confirmedPass(
        LesObservation arrival,
        LesObservation first,
        LesObservation last,
        LesObservation confirmation,
        final long trip,
        final int target) {
        if (hasMissingSeats(arrival, last)) {
            return null;
        }
        return new LesPassEvent(arrival.vehicleId(), trip, target, arrival, last,
            confirmation.receivedAt(), first, confirmation);
    }

    private static boolean hasMissingSeats(LesObservation arrival, LesObservation departure) {
        return arrival == null || arrival.seats() == null || departure.seats() == null;
    }
}
