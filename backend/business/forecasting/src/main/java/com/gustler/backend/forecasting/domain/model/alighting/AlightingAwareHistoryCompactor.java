package com.gustler.backend.forecasting.domain.model.alighting;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

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
import java.util.stream.Stream;

/** 오래된 관측을 누적 상태로 압축하되 최근 관측과 미완료 통과 구간은 남겨 예보 입력을 복구한다. */
public final class AlightingAwareHistoryCompactor {

    private static final Duration HISTORY_WINDOW = Duration.ofMinutes(90);
    private static final int PRECEDING_PASSES_PER_TRIP = 5;
    private static final Comparator<AlightingAwarePassEvent> BY_DEPARTURE_TIME =
        Comparator.comparing(event -> event.departure().receivedAt());

    private AlightingAwareHistoryCompactor() {
    }

    public record History(
        Map<String, AlightingAwareVehiclePrefix> prefix,
        List<AlightingAwareObservation> observations,
        List<AlightingAwarePassEvent> events
    ) {

        public History {
            prefix = Map.copyOf(prefix);
            observations = List.copyOf(observations);
            events = List.copyOf(events);
        }
    }

    public static History compact(
        History input,
        Instant asOf
    ) {
        Instant observationWindowStart = retainedFrom(input.observations(), asOf.minus(HISTORY_WINDOW));
        Map<String, AlightingAwareVehiclePrefix> prefixByVehicle = new HashMap<>(input.prefix());
        List<AlightingAwareObservation> observationsInWindow = new ArrayList<>();
        for (AlightingAwareObservation observation : input.observations()) {
            if (observation.receivedAt().isBefore(observationWindowStart)) {
                String vehicleId = observation.vehicleId();
                prefixByVehicle.put(vehicleId, advance(prefixByVehicle.get(vehicleId), observation));
                continue;
            }
            observationsInWindow.add(observation);
        }

        Instant passWindowStart = observationWindowStart;
        if (observationsInWindow.stream().anyMatch(AlightingAwareObservation::confirmedProfile)) {
            passWindowStart = observationWindowStart.minus(HISTORY_WINDOW);
        }
        Set<Long> replayTripIds = tripIdsForReplay(prefixByVehicle, observationsInWindow);
        List<AlightingAwarePassEvent> passes = passesForReplay(input.events(), replayTripIds, passWindowStart);
        return new History(prefixByVehicle, observationsInWindow, passes);
    }

    private static Instant retainedFrom(List<AlightingAwareObservation> observations, Instant windowStart) {
        Map<String, AlightingAwareObservation> lastObservationByVehicle = new HashMap<>();
        Map<String, Instant> chainStartByVehicle = new HashMap<>();
        for (AlightingAwareObservation observation : observations) {
            if (!observation.hasVehicleIdentity()) {
                continue;
            }
            String vehicleId = observation.vehicleId();
            AlightingAwareObservation previous = lastObservationByVehicle.put(vehicleId, observation);
            if (startsChain(previous, observation)) {
                chainStartByVehicle.put(vehicleId, observation.receivedAt());
            }
        }
        return earliestActiveChain(lastObservationByVehicle, chainStartByVehicle, windowStart);
    }

    private static boolean startsChain(AlightingAwareObservation previous, AlightingAwareObservation observation) {
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
        Map<String, AlightingAwareObservation> lastObservationByVehicle,
        Map<String, Instant> chainStartByVehicle,
        Instant windowStart
    ) {
        return lastObservationByVehicle.values().stream()
            .filter(observation -> !observation.receivedAt().isBefore(windowStart))
            .map(observation -> chainStartByVehicle.get(observation.vehicleId()))
            .filter(start -> start.isBefore(windowStart))
            .min(Comparator.naturalOrder())
            .orElse(windowStart);
    }

    private static Set<Long> tripIdsForReplay(
        Map<String, AlightingAwareVehiclePrefix> prefixByVehicle,
        List<AlightingAwareObservation> observationsInWindow
    ) {
        Set<Long> tripIds = prefixByVehicle.values().stream()
            .map(AlightingAwareVehiclePrefix::tripId)
            .collect(toCollection(HashSet::new));

        Map<String, AlightingAwareVehiclePrefix> stateByVehicle = new HashMap<>(prefixByVehicle);
        for (AlightingAwareObservation observation : observationsInWindow) {
            String vehicleId = observation.vehicleId();
            AlightingAwareVehiclePrefix state = advance(stateByVehicle.get(vehicleId), observation);
            stateByVehicle.put(vehicleId, state);
            tripIds.add(state.tripId());
        }
        return tripIds;
    }

    /** 조회 범위 안의 통과를 먼저 등록하고, 복구에 필요한 직전 통과가 같은 정류장을 가리키면 그 값으로 덮어쓴다. */
    private static List<AlightingAwarePassEvent> passesForReplay(
        List<AlightingAwarePassEvent> passes,
        Set<Long> replayTripIds,
        Instant windowStart
    ) {
        Map<String, List<AlightingAwarePassEvent>> earlierPassesByTrip = passes.stream()
            .filter(pass -> pass.departure().receivedAt().isBefore(windowStart))
            .filter(pass -> replayTripIds.contains(pass.tripId()))
            .collect(groupingBy(AlightingAwareHistoryCompactor::vehicleTripKey, HashMap::new, toList()));

        Stream<AlightingAwarePassEvent> passesInWindow = passes.stream()
            .filter(pass -> !pass.departure().receivedAt().isBefore(windowStart));
        Stream<AlightingAwarePassEvent> precedingPasses = earlierPassesByTrip.values().stream()
            .flatMap(AlightingAwareHistoryCompactor::lastFivePasses);
        return mergePassesByTripStop(passesInWindow, precedingPasses);
    }

    private static Stream<AlightingAwarePassEvent> lastFivePasses(List<AlightingAwarePassEvent> tripPasses) {
        final int skipCount = Math.max(0, tripPasses.size() - PRECEDING_PASSES_PER_TRIP);
        return tripPasses.stream().sorted(BY_DEPARTURE_TIME).skip(skipCount);
    }

    public static AlightingAwareVehiclePrefix advance(
        AlightingAwareVehiclePrefix state,
        AlightingAwareObservation observation
    ) {
        AlightingAwareObservation previous = previous(state);
        final int difference = passedDifference(previous, observation);
        final boolean linked = isLinked(previous, observation, difference);
        final double streak = fullStreak(
                state,
                observation,
                linked,
                difference
        );
        Instant entry = entryAt(state, observation, difference);
        final long tripId = tripId(state, observation, difference);
        return new AlightingAwareVehiclePrefix(
                0,
                streak,
                observation,
                entry,
                tripId,
                null
        );
    }

    private static AlightingAwareObservation previous(AlightingAwareVehiclePrefix state) {
        if (state == null) {
            return null;
        }
        return state.previous();
    }

    private static int passedDifference(AlightingAwareObservation previous, AlightingAwareObservation observation) {
        if (previous == null) {
            return 0;
        }
        return observation.passedStopOrder() - previous.passedStopOrder();
    }

    private static boolean isLinked(
        AlightingAwareObservation previous,
        AlightingAwareObservation observation,
        final int difference
    ) {
        if (observation.confirmedProfile() && observation.startsConfirmedChainAfter(previous)) {
            return false;
        }
        if (previous == null || observation.batchRank() - previous.batchRank() != 1) {
            return false;
        }
        return difference >= 0 && difference <= 1;
    }

    private static double fullStreak(
        AlightingAwareVehiclePrefix state,
        AlightingAwareObservation observation,
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

    private static boolean continuesFullStreak(AlightingAwareVehiclePrefix state, final boolean linked) {
        if (!linked) {
            return false;
        }
        return state.previous().seats() != null && state.previous().seats() == 0;
    }

    private static Instant entryAt(
        AlightingAwareVehiclePrefix state,
        AlightingAwareObservation observation,
        final int difference
    ) {
        if (observation.confirmedProfile() && observation.startsConfirmedChainAfter(previous(state))) {
            return observation.receivedAt();
        }
        if (state == null || difference != 0) {
            return observation.receivedAt();
        }
        return state.entryAt();
    }

    private static long tripId(
        AlightingAwareVehiclePrefix state,
        AlightingAwareObservation observation,
        final int difference
    ) {
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

    public static List<AlightingAwarePassEvent> resolve(
        History history,
        List<Integer> realStops
    ) {
        Map<Long, Long> tripByObservationId = tripsByObservation(history);
        List<AlightingAwarePassEvent> newPasses = AlightingAwarePassEvents.resolve(history.observations(), realStops);
        Stream<AlightingAwarePassEvent> passesWithRestoredTrip = newPasses.stream()
            .map(pass -> pass.withTripId(tripByObservationId.get(pass.departure().observationId())));
        return mergePassesByTripStop(history.events().stream(), passesWithRestoredTrip);
    }

    private static Map<Long, Long> tripsByObservation(History history) {
        Map<String, AlightingAwareVehiclePrefix> stateByVehicle = new HashMap<>(history.prefix());
        Map<Long, Long> tripByObservationId = new HashMap<>();
        for (AlightingAwareObservation observation : history.observations()) {
            String vehicleId = observation.vehicleId();
            AlightingAwareVehiclePrefix state = advance(stateByVehicle.get(vehicleId), observation);
            stateByVehicle.put(vehicleId, state);
            tripByObservationId.put(observation.observationId(), state.tripId());
        }
        return tripByObservationId;
    }

    private static List<AlightingAwarePassEvent> mergePassesByTripStop(
        Stream<AlightingAwarePassEvent> originalPasses,
        Stream<AlightingAwarePassEvent> overridingPasses
    ) {
        Map<String, AlightingAwarePassEvent> passByTripStop = Stream.concat(originalPasses, overridingPasses)
            .collect(toMap(
                    AlightingAwareHistoryCompactor::tripStopKey,
                    identity(),
                    (first, last) -> last,
                    LinkedHashMap::new
            ));
        return passByTripStop.values().stream().sorted(BY_DEPARTURE_TIME).toList();
    }

    private static boolean gap(
        AlightingAwareObservation previous,
        AlightingAwareObservation observation,
        final long seconds
    ) {
        return Duration.between(previous.receivedAt(), observation.receivedAt())
            .compareTo(Duration.ofSeconds(seconds)) > 0;
    }

    private static String vehicleTripKey(AlightingAwarePassEvent event) {
        return event.vehicleId() + ":" + event.tripId();
    }

    private static String tripStopKey(AlightingAwarePassEvent event) {
        return vehicleTripKey(event) + ":" + event.stopOrder();
    }
}
