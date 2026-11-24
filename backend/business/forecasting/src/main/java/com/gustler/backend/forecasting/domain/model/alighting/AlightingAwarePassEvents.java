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

    public static List<AlightingAwarePassEvent> resolve(
        List<AlightingAwareObservation> observations,
        List<Integer> realStopOrders
    ) {
        List<Integer> stops = realStopOrders.stream()
            .distinct()
            .sorted()
            .toList();
        Map<String, AlightingAwarePassEvent> eventsByTripStop = new LinkedHashMap<>();
        List<AlightingAwareObservation> chain = new ArrayList<>();
        AlightingAwareObservation previous = null;
        long tripId = 0;
        for (AlightingAwareObservation observation : orderedIdentifiedObservations(observations)) {
            Duration gap = observationGap(previous, observation);
            if (startsChain(previous, observation, gap)) {
                recordConfirmedPasses(
                        chain,
                        tripId,
                        stops,
                        eventsByTripStop
                );
                chain.clear();
            }

            if (startsTrip(previous, observation, gap)) {
                tripId = observation.observationId();
            }
            chain.add(observation);
            previous = observation;
        }

        recordConfirmedPasses(
                chain,
                tripId,
                stops,
                eventsByTripStop
        );
        return eventsByTripStop.values().stream()
            .sorted(Comparator.comparing(AlightingAwarePassEvent::confirmedAt)
                .thenComparingLong(event -> event.departure().observationId()))
            .toList();
    }

    private static List<AlightingAwareObservation> orderedIdentifiedObservations(
        List<AlightingAwareObservation> observations
    ) {
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

    private static boolean startsChain(
        AlightingAwareObservation previous,
        AlightingAwareObservation current,
        Duration gap
    ) {
        if (current.confirmedProfile()) {
            return current.startsConfirmedChainAfter(previous);
        }
        return changedVehicle(previous, current)
            || gap.compareTo(Duration.ofSeconds(90)) > 0
            || movedBackwards(previous, current);
    }

    private static boolean startsTrip(
        AlightingAwareObservation previous,
        AlightingAwareObservation current,
        Duration gap
    ) {
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

    private static void recordConfirmedPasses(
        List<AlightingAwareObservation> chain,
        final long tripId,
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
            final int previousStop = previousStopOrder(stops, index);
            final int nextStop = stops.get(index + 1);
            AlightingAwarePassEvent event = resolvePass(
                    chain,
                    tripId,
                    target,
                    previousStop,
                    nextStop
            );
            if (event == null) {
                continue;
            }

            String tripStopKey = event.vehicleId() + ":" + tripId + ":" + target;
            if (event.arrival().confirmedProfile() && eventsByTripStop.containsKey(tripStopKey)) {
                throw new IllegalArgumentException("Ambiguous confirmed trip/stop visit");
            }
            eventsByTripStop.put(tripStopKey, event);
        }
    }

    private static int previousStopOrder(List<Integer> stops, final int index) {
        if (index == 0) {
            return 0;
        }
        return stops.get(index - 1);
    }

    /** 작은 순번 후퇴는 허용하되, 마지막 도착 전 관측보다 앞선 출발 관측을 연결하지 않는다. */
    private static AlightingAwarePassEvent resolvePass(
        List<AlightingAwareObservation> chain,
        final long tripId,
        final int target,
        final int previousStop,
        final int nextStop
    ) {
        AlightingAwareObservation arrival = null;
        AlightingAwareObservation firstDeparture = null;
        AlightingAwareObservation lastDeparture = null;
        for (AlightingAwareObservation observation : chain) {
            if (isBeforeTarget(observation, previousStop, target)) {
                arrival = observation;
                firstDeparture = null;
                lastDeparture = null;
                continue;
            }
            if (arrival != null && isInDepartureRange(observation, target, nextStop)) {
                if (firstDeparture == null) {
                    firstDeparture = observation;
                }
                lastDeparture = observation;
                continue;
            }
            if (lastDeparture != null && observation.passedStopOrder() >= nextStop) {
                return confirmedPass(
                        arrival,
                        firstDeparture,
                        lastDeparture,
                        observation,
                        tripId,
                        target
                );
            }
        }
        return null;
    }

    private static boolean isBeforeTarget(
        AlightingAwareObservation observation,
        final int previousStop,
        final int target
    ) {
        return observation.passedStopOrder() >= previousStop && observation.passedStopOrder() < target;
    }

    private static boolean isInDepartureRange(
        AlightingAwareObservation observation,
        final int target,
        final int nextStop
    ) {
        return observation.passedStopOrder() >= target && observation.passedStopOrder() < nextStop;
    }

    private static AlightingAwarePassEvent confirmedPass(
        AlightingAwareObservation arrival,
        AlightingAwareObservation firstDeparture,
        AlightingAwareObservation lastDeparture,
        AlightingAwareObservation confirmation,
        final long tripId,
        final int target
    ) {
        if (hasMissingSeats(arrival, lastDeparture)) {
            return null;
        }
        return new AlightingAwarePassEvent(
                arrival.vehicleId(),
                tripId,
                target,
                arrival,
                lastDeparture,
                confirmation.receivedAt(),
                firstDeparture,
                confirmation
        );
    }

    private static boolean hasMissingSeats(AlightingAwareObservation arrival, AlightingAwareObservation departure) {
        return arrival == null || arrival.seats() == null || departure.seats() == null;
    }
}
