package com.gustler.backend.forecasting.infrastructure.alighting;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureUnavailableException;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareHistoryCompactor;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareVehiclePrefix;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 체크포인트 JSON의 필수 값·타입·관측 시점·사건 연결을 검증한다. 파일 잠금과 저장은 담당하지 않는다. */
final class AlightingAwareHistorySeedCodec {
    private static final List<String> OBSERVATION_FIELDS = List.of("id", "batch_id", "batch_rank", "received_at",
        "vehicle_id", "passed_stop_order", "stop_order", "running_state", "remaining_seats", "crowd_level");

    private AlightingAwareHistorySeedCodec() {
    }

    static AlightingAwareHistorySeedReader.Seed decode(JsonNode root, final long routeVersionId, final int maximumRows) {
        validateContract(root, routeVersionId);
        requireFields(root, List.of("through_batch_id", "through_batch_rank", "through_received_at", "observations"));
        validateCompactState(root);
        Cursor cursor = cursor(root);
        List<AlightingAwareObservation> rows = observations(root.path("observations"), cursor, maximumRows);
        Map<String, AlightingAwareVehiclePrefix> prefix = prefixes(root.path("prefix"), cursor);
        List<AlightingAwarePassEvent> events = events(root.path("events"), cursor);
        validateRetainedWindow(rows, prefix);
        return new AlightingAwareHistorySeedReader.Seed(rows, cursor.batch(), cursor.rank(), cursor.through(), prefix, events);
    }

    private static void validateContract(JsonNode root, final long routeVersionId) {
        if (!List.of("alighting-complete-history-v1", "alighting-compact-history-v2").contains(canonical(root.path("schema").asText()))) {
            throw unavailable("ALIGHTING_AWARE_SEED_CONTRACT_MISMATCH");
        }
        if (root.path("route_version_id").asLong() != routeVersionId
            || !root.path("complete_from_source_origin").asBoolean()) {
            throw unavailable("ALIGHTING_AWARE_SEED_CONTRACT_MISMATCH");
        }
    }

    private static void validateCompactState(JsonNode root) {
        if (!canonical(root.path("schema").asText()).equals("alighting-compact-history-v2")) {
            return;
        }
        if (!root.path("prefix").isArray() || !root.path("events").isArray()) {
            throw unavailable("ALIGHTING_AWARE_SEED_COMPACT_STATE_MISSING");
        }
    }

    private static Cursor cursor(JsonNode root) {
        if (!hasCursorTypes(root)) {
            throw unavailable("ALIGHTING_AWARE_SEED_INVALID_CURSOR");
        }
        final long batch = root.path("through_batch_id").asLong();
        final long rank = root.path("through_batch_rank").asLong();
        if (batch <= 0 || rank < 0) {
            throw unavailable("ALIGHTING_AWARE_SEED_INVALID_CURSOR");
        }
        return new Cursor(batch, rank, Instant.parse(root.path("through_received_at").asText()));
    }

    private static boolean hasCursorTypes(JsonNode root) {
        if (!root.path("through_batch_id").isIntegralNumber() || !root.path("through_batch_rank").isIntegralNumber()) {
            return false;
        }
        return root.path("observations").isArray();
    }

    private static List<AlightingAwareObservation> observations(JsonNode rows, Cursor cursor, final int maximumRows) {
        List<AlightingAwareObservation> observations = new ArrayList<>();
        for (JsonNode row : rows) {
            if (observations.size() >= maximumRows) {
                throw unavailable("ALIGHTING_AWARE_HISTORY_ROW_LIMIT_EXCEEDED");
            }
            requireFields(row, OBSERVATION_FIELDS);
            AlightingAwareObservation observation = observation(row);
            if (cursor.isFuture(observation)) {
                throw unavailable("ALIGHTING_AWARE_SEED_FUTURE_ROW");
            }
            observations.add(observation);
        }
        observations.sort(Comparator.comparing(AlightingAwareObservation::receivedAt)
            .thenComparingLong(AlightingAwareObservation::batchId).thenComparing(AlightingAwareObservation::vehicleId));
        return observations;
    }

    private static Map<String, AlightingAwareVehiclePrefix> prefixes(JsonNode states, Cursor cursor) {
        Map<String, AlightingAwareVehiclePrefix> prefixes = new HashMap<>();
        for (JsonNode state : states) {
            AlightingAwareVehiclePrefix prefix = prefix(state, cursor);
            if (prefixes.put(prefix.previous().vehicleId(), prefix) != null) {
                throw unavailable("ALIGHTING_AWARE_SEED_DUPLICATE_PREFIX");
            }
        }
        return prefixes;
    }

    private static AlightingAwareVehiclePrefix prefix(JsonNode state, Cursor cursor) {
        requireFields(state, List.of("capacity", "full_streak", "previous", "entry_at", "trip_id"));
        AlightingAwareObservation previous = observation(state.path("previous"));
        cursor.requireEvidence(previous);
        Instant entry = Instant.parse(state.path("entry_at").asText());
        if (entry.isAfter(previous.receivedAt())) {
            throw unavailable("ALIGHTING_AWARE_SEED_FUTURE_ENTRY");
        }
        if (!hasPrefixTypes(state)) {
            throw unavailable("ALIGHTING_AWARE_SEED_INVALID_PREFIX");
        }
        return new AlightingAwareVehiclePrefix((float) state.path("capacity").asDouble(), state.path("full_streak").asDouble(),
            previous, entry, state.path("trip_id").asLong(), capacityObservation(state, previous, cursor));
    }

    private static boolean hasPrefixTypes(JsonNode state) {
        if (!state.path("capacity").isNumber() || !state.path("full_streak").isNumber()) {
            return false;
        }
        return state.path("trip_id").isIntegralNumber();
    }

    private static List<AlightingAwarePassEvent> events(JsonNode rows, Cursor cursor) {
        List<AlightingAwarePassEvent> events = new ArrayList<>();
        for (JsonNode row : rows) {
            events.add(event(row, cursor));
        }
        return events;
    }

    private static AlightingAwarePassEvent event(JsonNode row, Cursor cursor) {
        requireFields(row, List.of("vehicle_id", "trip_id", "stop_order", "arrival", "departure", "confirmation",
            "first_departure"));
        AlightingAwareObservation arrival = observation(row.path("arrival"));
        AlightingAwareObservation departure = observation(row.path("departure"));
        AlightingAwareObservation confirmation = observation(row.path("confirmation"));
        AlightingAwareObservation first = observation(row.path("first_departure"));
        String vehicle = row.path("vehicle_id").asText();
        validateEventEvidence(List.of(arrival, departure, confirmation, first), vehicle, cursor);
        validateEventSequence(arrival, first, departure, confirmation);
        return new AlightingAwarePassEvent(vehicle, row.path("trip_id").asLong(), row.path("stop_order").asInt(), arrival,
            departure, confirmation.receivedAt(), first, confirmation);
    }

    private static void validateEventEvidence(List<AlightingAwareObservation> observations, String vehicle, Cursor cursor) {
        for (AlightingAwareObservation evidence : observations) {
            cursor.requireEvidence(evidence);
            if (!evidence.vehicleId().equals(vehicle)) {
                throw unavailable("ALIGHTING_AWARE_SEED_EVENT_VEHICLE_MISMATCH");
            }
        }
    }

    private static void validateEventSequence(
        AlightingAwareObservation arrival,
        AlightingAwareObservation first,
        AlightingAwareObservation departure,
        AlightingAwareObservation confirmation
    ) {
        if (!hasOrderedEventTimes(arrival, first, departure, confirmation)) {
            throw unavailable("ALIGHTING_AWARE_SEED_EVENT_TIME_ORDER");
        }
        if (!arrival.hasVehicleIdentity()) {
            throw unavailable("ALIGHTING_AWARE_SEED_ANONYMOUS_PASS_EVENT");
        }
        if (arrival.seats() == null || departure.seats() == null) {
            throw unavailable("ALIGHTING_AWARE_SEED_EVENT_SEATS_UNKNOWN");
        }
    }

    private static boolean hasOrderedEventTimes(
        AlightingAwareObservation arrival,
        AlightingAwareObservation first,
        AlightingAwareObservation departure,
        AlightingAwareObservation confirmation
    ) {
        if (arrival.receivedAt().isAfter(first.receivedAt()) || first.receivedAt().isAfter(departure.receivedAt())) {
            return false;
        }
        return !departure.receivedAt().isAfter(confirmation.receivedAt());
    }

    private static void validateRetainedWindow(List<AlightingAwareObservation> rows, Map<String, AlightingAwareVehiclePrefix> prefix) {
        Set<Long> identities = new HashSet<>();
        Set<String> vehicleBatches = new HashSet<>();
        for (AlightingAwareObservation observation : rows) {
            if (!identities.add(observation.observationId())
                || !vehicleBatches.add(observation.vehicleId() + ":" + observation.batchId())) {
                throw unavailable("ALIGHTING_AWARE_SEED_DUPLICATE_OBSERVATION");
            }
            if (overlapsPrefix(observation, prefix.get(observation.vehicleId()))) {
                throw unavailable("ALIGHTING_AWARE_SEED_PREFIX_OVERLAPS_WINDOW");
            }
        }
    }

    private static boolean overlapsPrefix(AlightingAwareObservation observation, AlightingAwareVehiclePrefix prior) {
        if (prior == null) {
            return false;
        }
        return !prior.previous().receivedAt().isBefore(observation.receivedAt())
            || prior.previous().batchRank() >= observation.batchRank();
    }

    static AlightingAwareObservation observation(JsonNode row) {
        requireFields(row, OBSERVATION_FIELDS);
        for (String field : List.of("id", "batch_id", "batch_rank", "passed_stop_order", "stop_order",
            "running_state")) {
            if (!row.path(field).isIntegralNumber()) {
                throw unavailable("ALIGHTING_AWARE_SEED_INVALID_FIELD:" + field);
            }
        }
        if (!row.path("remaining_seats").isNull() && !row.path("remaining_seats").isIntegralNumber()) {
            throw unavailable("ALIGHTING_AWARE_SEED_INVALID_FIELD:remaining_seats");
        }
        if (row.path("vehicle_id").asText().isBlank()) {
            throw unavailable("ALIGHTING_AWARE_SEED_INVALID_VEHICLE");
        }
        if (row.path("route_version_id").asLong() > 0) {
            requireFields(row, List.of("physical_received_at", "down"));
            return new AlightingAwareObservation(row.path("id").asLong(), row.path("batch_id").asLong(),
                row.path("batch_rank").asLong(), Instant.parse(row.path("received_at").asText()),
                row.path("vehicle_id").asText(), row.path("passed_stop_order").asInt(),
                row.path("stop_order").asInt(), row.path("running_state").asInt(),
                seats(row.path("remaining_seats")), row.path("crowd_level").asInt(),
                row.path("route_version_id").asLong(), Instant.parse(row.path("physical_received_at").asText()),
                row.path("down").asBoolean());
        }
        return new AlightingAwareObservation(row.path("id").asLong(), row.path("batch_id").asLong(),
            row.path("batch_rank").asLong(),
            Instant.parse(row.path("received_at").asText()), row.path("vehicle_id").asText(),
            row.path("passed_stop_order").asInt(), row.path("stop_order").asInt(), row.path("running_state").asInt(),
            seats(row.path("remaining_seats")), row.path("crowd_level").asInt());
    }

    private static Integer seats(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        return node.asInt();
    }

    private static void requireFields(JsonNode node, List<String> fields) {
        for (String field : fields) {
            if (!node.has(field)) {
                throw unavailable("ALIGHTING_AWARE_SEED_MISSING_FIELD:" + field);
            }
        }
    }

    static Map<String, Object> encode(
        final long routeVersionId,
        AlightingAwareHistorySeedReader.Seed seed,
        AlightingAwareHistoryCompactor.History compact) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "alighting-compact-history-v2");
        payload.put("route_version_id", routeVersionId);
        payload.put("complete_from_source_origin", true);
        payload.put("through_batch_id", seed.batchId());
        payload.put("through_batch_rank", seed.rank());
        payload.put("through_received_at", seed.through().toString());
        payload.put("observations", compact.observations().stream().map(AlightingAwareHistorySeedCodec::row).toList());
        payload.put("prefix", compact.prefix().values().stream().map(AlightingAwareHistorySeedCodec::prefixRow).toList());
        payload.put("events", compact.events().stream().map(AlightingAwareHistorySeedCodec::eventRow).toList());
        return payload;
    }

    private static Map<String, Object> prefixRow(AlightingAwareVehiclePrefix state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capacity", state.capacity());
        result.put("full_streak", state.fullStreak());
        result.put("previous", row(state.previous()));
        result.put("entry_at", state.entryAt().toString());
        result.put("trip_id", state.tripId());
        if (state.capacityObservation() != null) {
            result.put("capacity_observation", row(state.capacityObservation()));
        }
        return result;
    }

    private static AlightingAwareObservation capacityObservation(JsonNode state, AlightingAwareObservation previous, Cursor cursor) {
        if (!state.has("capacity_observation")) {
            return null;
        }
        AlightingAwareObservation capacity = observation(state.path("capacity_observation"));
        cursor.requireEvidence(capacity);
        if (!capacity.vehicleId().equals(previous.vehicleId()) || capacity.seats() == null) {
            throw unavailable("ALIGHTING_AWARE_SEED_CAPACITY_EVIDENCE_MISMATCH");
        }
        if (capacity.seats() != state.path("capacity").asInt() || capacity.receivedAt().isAfter(previous.receivedAt())) {
            throw unavailable("ALIGHTING_AWARE_SEED_CAPACITY_EVIDENCE_MISMATCH");
        }
        return capacity;
    }

    private static Map<String, Object> eventRow(AlightingAwarePassEvent event) {
        return Map.of("vehicle_id", event.vehicleId(), "trip_id", event.tripId(), "stop_order", event.stopOrder(),
            "arrival", row(event.arrival()), "departure", row(event.departure()), "first_departure",
            row(event.firstDeparture()),
            "confirmation", row(event.confirmation()));
    }

    private static Map<String, Object> row(AlightingAwareObservation observation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", observation.observationId());
        row.put("batch_id", observation.batchId());
        row.put("batch_rank", observation.batchRank());
        row.put("received_at", observation.receivedAt().toString());
        row.put("vehicle_id", observation.vehicleId());
        row.put("passed_stop_order", observation.passedStopOrder());
        row.put("stop_order", observation.stopOrder());
        row.put("running_state", observation.state());
        row.put("remaining_seats", observation.seats());
        row.put("crowd_level", observation.crowd());
        if (observation.confirmedProfile()) {
            row.put("route_version_id", observation.routeVersionId());
            row.put("physical_received_at", observation.sourceReceivedAt().toString());
            row.put("down", observation.down());
        }
        return row;
    }

    private record Cursor(long batch, long rank, Instant through) {
        private boolean isFuture(AlightingAwareObservation observation) {
            if (observation.batchRank() > rank || observation.receivedAt().isAfter(through)) {
                return true;
            }
            return observation.receivedAt().equals(through) && observation.batchId() > batch;
        }

        private void requireEvidence(AlightingAwareObservation observation) {
            if (isFuture(observation)) {
                throw unavailable("ALIGHTING_AWARE_SEED_FUTURE_EVIDENCE");
            }
        }
    }

    private static AlightingAwareFeatureUnavailableException unavailable(String reason) {
        return new AlightingAwareFeatureUnavailableException(reason);
    }
}
