package com.gustler.backend.forecasting.domain.model.les;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 누적 상태와 시간 창·미완료 통과 구간을 함께 보존해 이전 입력을 정확히 복구한다. */
public final class LesHistoryCompactor {
    private LesHistoryCompactor() {
    }

    public record History(
        Map<String, LesVehiclePrefix> prefix,
        List<LesObservation> observations,
        List<LesPassEvent> events
    ) {
        public History {
            prefix = Map.copyOf(prefix);
            observations = List.copyOf(observations);
            events = List.copyOf(events);
        }
    }

    public static History compact(History input, Instant asOf) {
        Instant cutoff = retainedFrom(input.observations(), asOf.minusSeconds(5400));
        Map<String, LesVehiclePrefix> accumulated = new HashMap<>(input.prefix());
        List<LesObservation> retained = new ArrayList<>();
        for (LesObservation observation : input.observations()) {
            if (observation.receivedAt().isBefore(cutoff)) {
                accumulated.put(observation.vehicleId(),
                    advance(accumulated.get(observation.vehicleId()), observation));
                continue;
            }
            retained.add(observation);
        }
        Instant eventCutoff = cutoff;
        if (retained.stream().anyMatch(LesObservation::confirmedProfile)) {
            eventCutoff = cutoff.minusSeconds(5400);
        }
        return new History(accumulated, retained, retainedEvents(input.events(), accumulated, retained, eventCutoff));
    }

    private static Instant retainedFrom(List<LesObservation> observations, Instant windowStart) {
        Map<String, LesObservation> previousByVehicle = new HashMap<>();
        Map<String, Instant> chainStartsByVehicle = new HashMap<>();
        for (LesObservation observation : observations) {
            if (!observation.hasVehicleIdentity()) {
                continue;
            }
            LesObservation previous = previousByVehicle.put(observation.vehicleId(), observation);
            if (startsChain(previous, observation)) {
                chainStartsByVehicle.put(observation.vehicleId(), observation.receivedAt());
            }
        }
        return earliestActiveChain(previousByVehicle, chainStartsByVehicle, windowStart);
    }

    private static boolean startsChain(LesObservation previous, LesObservation observation) {
        if (observation.confirmedProfile()) {
            return observation.startsConfirmedChainAfter(previous);
        }
        if (previous == null || gap(previous, observation, 90)) {
            return true;
        }
        return observation.passedStopOrder() < previous.passedStopOrder() - 5;
    }

    /** 90분보다 오래 정차 중인 차도 마지막 도착 근거를 잃지 않도록 열린 구간을 모두 보관한다. */
    private static Instant earliestActiveChain(
        Map<String, LesObservation> previousByVehicle,
        Map<String, Instant> chainStartsByVehicle,
        Instant windowStart) {
        Instant cutoff = windowStart;
        for (Map.Entry<String, LesObservation> entry : previousByVehicle.entrySet()) {
            if (entry.getValue().receivedAt().isBefore(windowStart)) {
                continue;
            }
            Instant start = chainStartsByVehicle.get(entry.getKey());
            if (start.isBefore(cutoff)) {
                cutoff = start;
            }
        }
        return cutoff;
    }

    private static List<LesPassEvent> retainedEvents(
        List<LesPassEvent> sourceEvents,
        Map<String, LesVehiclePrefix> accumulated,
        List<LesObservation> retained,
        Instant cutoff
    ) {
        Set<Long> neededTrips = neededTrips(accumulated, retained);
        Map<String, List<LesPassEvent>> precedingByTrip = new HashMap<>();
        for (LesPassEvent event : sourceEvents) {
            if (event.departure().receivedAt().isBefore(cutoff) && neededTrips.contains(event.tripId())) {
                precedingByTrip.computeIfAbsent(tripKey(event), ignored -> new ArrayList<>()).add(event);
            }
        }
        Map<String, LesPassEvent> eventsByKey = new LinkedHashMap<>();
        for (LesPassEvent event : sourceEvents) {
            if (!event.departure().receivedAt().isBefore(cutoff)) {
                eventsByKey.put(key(event), event);
            }
        }
        retainFivePrecedingEvents(precedingByTrip, eventsByKey);
        return orderedEvents(eventsByKey);
    }

    private static Set<Long> neededTrips(
        Map<String, LesVehiclePrefix> accumulated,
        List<LesObservation> retained) {
        Set<Long> tripIds = new HashSet<>();
        for (LesVehiclePrefix state : accumulated.values()) {
            tripIds.add(state.tripId());
        }
        Map<String, LesVehiclePrefix> states = new HashMap<>(accumulated);
        for (LesObservation observation : retained) {
            LesVehiclePrefix state = advance(states.get(observation.vehicleId()), observation);
            states.put(observation.vehicleId(), state);
            tripIds.add(state.tripId());
        }
        return tripIds;
    }

    /** 보존 창 안의 과거 미발행 배치도 복구할 수 있도록 창 직전 사건 다섯 개를 남긴다. */
    private static void retainFivePrecedingEvents(
        Map<String, List<LesPassEvent>> precedingByTrip,
        Map<String, LesPassEvent> eventsByKey) {
        for (List<LesPassEvent> events : precedingByTrip.values()) {
            events.sort(Comparator.comparing(event -> event.departure().receivedAt()));
            for (LesPassEvent event : events.subList(Math.max(0, events.size() - 5), events.size())) {
                eventsByKey.put(key(event), event);
            }
        }
    }

    public static LesVehiclePrefix advance(LesVehiclePrefix state, LesObservation observation) {
        LesObservation previous = previous(state);
        final int difference = passedDifference(previous, observation);
        final boolean linked = isLinked(previous, observation, difference);
        final double streak = fullStreak(state, observation, linked, difference);
        final float capacity = capacity(state, observation);
        Instant entry = entryAt(state, observation, difference);
        final long tripId = tripId(state, observation, difference);
        LesObservation capacityObservation = null;
        if (state != null) {
            capacityObservation = state.capacityObservation();
        }
        if (hasValidCapacity(observation) && (state == null || observation.seats() > state.capacity())) {
            capacityObservation = observation;
        }
        return new LesVehiclePrefix(capacity, streak, observation, entry, tripId, capacityObservation);
    }

    private static LesObservation previous(LesVehiclePrefix state) {
        if (state == null) {
            return null;
        }
        return state.previous();
    }

    private static int passedDifference(LesObservation previous, LesObservation observation) {
        if (previous == null) {
            return 0;
        }
        return observation.passedStopOrder() - previous.passedStopOrder();
    }

    private static boolean isLinked(
        LesObservation previous,
        LesObservation observation,
        final int difference) {
        if (observation.confirmedProfile() && observation.startsConfirmedChainAfter(previous)) {
            return false;
        }
        if (previous == null || observation.batchRank() - previous.batchRank() != 1) {
            return false;
        }
        return difference >= 0 && difference <= 1;
    }

    private static double fullStreak(
        LesVehiclePrefix state,
        LesObservation observation,
        final boolean linked,
        final int difference
    ) {
        if (observation.seats() == null || observation.seats() != 0) {
            return 0;
        }
        if (!continuesFullStreak(state, linked)) {
            return 1;
        }
        if (difference != 0) {
            return state.fullStreak() + 1;
        }
        return state.fullStreak() + 0;
    }

    private static boolean continuesFullStreak(LesVehiclePrefix state, final boolean linked) {
        if (!linked) {
            return false;
        }
        return state.previous().seats() != null && state.previous().seats() == 0;
    }

    private static float capacity(LesVehiclePrefix state, LesObservation observation) {
        float capacity = 0;
        if (state != null) {
            capacity = state.capacity();
        }
        if (hasValidCapacity(observation)) {
            capacity = Math.max(capacity, observation.seats());
        }
        return capacity;
    }

    private static boolean hasValidCapacity(LesObservation observation) {
        if (observation.seats() == null) {
            return false;
        }
        return observation.seats() > 0 && observation.seats() <= 70;
    }

    private static Instant entryAt(
        LesVehiclePrefix state,
        LesObservation observation,
        final int difference) {
        if (observation.confirmedProfile() && observation.startsConfirmedChainAfter(previous(state))) {
            return observation.receivedAt();
        }
        if (state == null || difference != 0) {
            return observation.receivedAt();
        }
        return state.entryAt();
    }

    private static long tripId(LesVehiclePrefix state, LesObservation observation, final int difference) {
        if (observation.confirmedProfile() && observation.startsConfirmedTripAfter(previous(state))) {
            return observation.observationId();
        }
        if (state == null || gap(state.previous(), observation, 1800)) {
            return observation.observationId();
        }
        if (difference < -5) {
            return observation.observationId();
        }
        return state.tripId();
    }

    public static List<LesPassEvent> resolve(History history, List<Integer> realStops) {
        Map<Long, Long> tripsByObservation = tripsByObservation(history);
        Map<String, LesPassEvent> eventsByKey = new LinkedHashMap<>();
        for (LesPassEvent event : history.events()) {
            eventsByKey.put(key(event), event);
        }
        for (LesPassEvent event : LesPassEvents.resolve(history.observations(), realStops)) {
            LesPassEvent corrected = new LesPassEvent(event.vehicleId(),
                tripsByObservation.get(event.departure().observationId()), event.stopOrder(), event.arrival(),
                event.departure(), event.confirmedAt(), event.firstDeparture(), event.confirmation());
            eventsByKey.put(key(corrected), corrected);
        }
        return orderedEvents(eventsByKey);
    }

    private static Map<Long, Long> tripsByObservation(History history) {
        Map<String, LesVehiclePrefix> states = new HashMap<>(history.prefix());
        Map<Long, Long> trips = new HashMap<>();
        for (LesObservation observation : history.observations()) {
            LesVehiclePrefix state = advance(states.get(observation.vehicleId()), observation);
            states.put(observation.vehicleId(), state);
            trips.put(observation.observationId(), state.tripId());
        }
        return trips;
    }

    private static List<LesPassEvent> orderedEvents(Map<String, LesPassEvent> eventsByKey) {
        return eventsByKey.values().stream()
            .sorted(Comparator.comparing(event -> event.departure().receivedAt()))
            .toList();
    }

    private static boolean gap(LesObservation previous, LesObservation observation, final long seconds) {
        return Duration.between(previous.receivedAt(), observation.receivedAt())
            .compareTo(Duration.ofSeconds(seconds)) > 0;
    }

    private static String tripKey(LesPassEvent event) {
        return event.vehicleId() + ":" + event.tripId();
    }

    private static String key(LesPassEvent event) {
        return tripKey(event) + ":" + event.stopOrder();
    }
}
