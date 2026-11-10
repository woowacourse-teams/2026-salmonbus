package com.gustler.backend.forecasting.domain.model.alighting;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** passes3의 30분 편도 / 90초 관측 연결 조건. 미래 관측 없는 열린 구간은 제외한다. */
public final class AlightingAwarePassEvents {
    private AlightingAwarePassEvents() {
    }

    public static List<AlightingAwarePassEvent> resolve(List<AlightingAwareObservation> observations, List<Integer> realStopOrders) {
        List<Integer> stops = realStopOrders.stream().distinct().sorted().toList();
        Map<String, AlightingAwarePassEvent> eventsByTripStop = new LinkedHashMap<>();
        List<AlightingAwareObservation> chain = new ArrayList<>();
        AlightingAwareObservation previous = null;
        long trip = 0;
        for (AlightingAwareObservation row : orderedIdentifiedObservations(observations)) {
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
            .sorted(Comparator.comparing(AlightingAwarePassEvent::confirmedAt)
                .thenComparingLong(event -> event.departure().observationId()))
            .toList();
    }

    private static List<AlightingAwareObservation> orderedIdentifiedObservations(List<AlightingAwareObservation> observations) {
        return observations.stream()
            .filter(AlightingAwareObservation::hasVehicleIdentity)
            .sorted(Comparator.comparing(AlightingAwareObservation::vehicleId)
                .thenComparing(AlightingAwareObservation::receivedAt)
                .thenComparingLong(AlightingAwareObservation::observationId))
            .toList();
    }

    private static Duration observationGap(AlightingAwareObservation previous, AlightingAwareObservation current) {
        if (previous == null) {
            return Duration.ZERO;
        }
        return Duration.between(previous.receivedAt(), current.receivedAt());
    }

    private static boolean startsChain(AlightingAwareObservation previous, AlightingAwareObservation current, Duration gap) {
        if (current.confirmedProfile()) {
            return current.startsConfirmedChainAfter(previous);
        }
        return changedVehicle(previous, current)
            || gap.compareTo(Duration.ofSeconds(90)) > 0
            || movedBackwards(previous, current);
    }

    private static boolean startsTrip(AlightingAwareObservation previous, AlightingAwareObservation current, Duration gap) {
        if (current.confirmedProfile()) {
            return current.startsConfirmedTripAfter(previous);
        }
        return changedVehicle(previous, current)
            || gap.compareTo(Duration.ofMinutes(30)) > 0
            || movedBackwards(previous, current);
    }

    private static boolean changedVehicle(AlightingAwareObservation previous, AlightingAwareObservation current) {
        return previous == null || !previous.vehicleId().equals(current.vehicleId());
    }

    private static boolean movedBackwards(AlightingAwareObservation previous, AlightingAwareObservation current) {
        return previous != null && current.passedStopOrder() < previous.passedStopOrder() - 5;
    }

    private static void addChain(
        List<AlightingAwareObservation> chain,
        final long trip,
        List<Integer> stops,
        Map<String, AlightingAwarePassEvent> eventsByTripStop
    ) {
        if (chain.size() < 3) {
            return;
        }
        int start = 0;
        if (chain.getFirst().confirmedProfile()) {
            start = 1;
        }
        for (int index = start; index + 1 < stops.size(); index++) {
            final int target = stops.get(index);
            final int before = previousStop(stops, index);
            final int next = stops.get(index + 1);
            AlightingAwarePassEvent event = resolvePass(chain, trip, target, before, next);
            if (event != null) {
                String key = event.vehicleId() + ":" + trip + ":" + target;
                if (event.arrival().confirmedProfile() && eventsByTripStop.containsKey(key)) {
                    throw new IllegalArgumentException("Ambiguous confirmed trip/stop visit");
                }
                eventsByTripStop.put(key, event);
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
    private static AlightingAwarePassEvent resolvePass(
        List<AlightingAwareObservation> chain,
        final long trip,
        final int target,
        final int before,
        final int next
    ) {
        AlightingAwareObservation arrival = null;
        AlightingAwareObservation first = null;
        AlightingAwareObservation last = null;
        for (AlightingAwareObservation row : chain) {
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

    private static boolean isBeforeTarget(AlightingAwareObservation row, final int before, final int target) {
        return row.passedStopOrder() >= before && row.passedStopOrder() < target;
    }

    private static boolean isAfterTarget(
        AlightingAwareObservation arrival,
        AlightingAwareObservation row,
        final int target,
        final int next
    ) {
        return arrival != null && row.passedStopOrder() >= target && row.passedStopOrder() < next;
    }

    private static AlightingAwarePassEvent confirmedPass(
        AlightingAwareObservation arrival,
        AlightingAwareObservation first,
        AlightingAwareObservation last,
        AlightingAwareObservation confirmation,
        final long trip,
        final int target
    ) {
        if (hasMissingSeats(arrival, last)) {
            return null;
        }
        return new AlightingAwarePassEvent(arrival.vehicleId(), trip, target, arrival, last,
            confirmation.receivedAt(), first, confirmation);
    }

    private static boolean hasMissingSeats(AlightingAwareObservation arrival, AlightingAwareObservation departure) {
        return arrival == null || arrival.seats() == null || departure.seats() == null;
    }
}
