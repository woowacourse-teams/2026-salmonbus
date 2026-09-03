package com.gustler.backend.migration.db;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.Sha256;
import com.gustler.backend.migration.archive.RouteRoster;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;

record RouteSeed(
    String publicRouteId,
    String sourceId,
    String sourceRouteId,
    String displayName,
    String startStopName,
    String endStopName,
    Integer turnSequence,
    String upFirstDepartureTime,
    String upLastDepartureTime,
    String downFirstDepartureTime,
    String downLastDepartureTime,
    String contentDigest,
    List<Stop> stops,
    String seedFileSha256
) {

    private static final String SCHEMA = "salmonbus-rds-route-seed-v1";

    RouteSeed {
        stops = List.copyOf(stops);
        if (!"234000050".equals(publicRouteId) || !"GBIS".equals(sourceId)
            || !publicRouteId.equals(sourceRouteId) || !"1650".equals(displayName)
            || turnSequence == null || turnSequence != 44 || stops.size() != 89) {
            throw new MigrationException("ROUTE_1650_SEED_IDENTITY_INVALID");
        }
        for (int index = 0; index < stops.size(); index++) {
            Stop stop = stops.get(index);
            if (stop.stopOrder() != index + 1
                || stop.boardingAllowed() != !stop.stopId().startsWith("277")
                || !stop.direction().equals(stop.stopOrder() <= turnSequence ? "UP" : "DOWN")) {
                throw new MigrationException("ROUTE_1650_SEED_STOP_INVALID");
            }
        }
        if (!contentDigest.equals(calculateContentDigest(turnSequence, stops))) {
            throw new MigrationException("ROUTE_1650_SEED_CONTENT_DIGEST_MISMATCH");
        }
        Sha256.requireDigest(seedFileSha256, "ROUTE_1650_SEED_FILE_DIGEST_INVALID");
    }

    static RouteSeed read(
        Path path,
        RouteRoster reference
    ) {
        if (path == null || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            throw new MigrationException("ROUTE_1650_SEED_REQUIRED");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new MigrationException("ROUTE_1650_SEED_READ_FAILED", e);
        }
        JsonNode root = CanonicalJson.parse(bytes, "ROUTE_1650_SEED_JSON_INVALID");
        if (!SCHEMA.equals(text(root, "schema_version"))
            || !"VALIDATED_REFERENCE_TARGET_VERSION_EXISTS_NO_INSERT".equals(text(root, "status"))) {
            throw new MigrationException("ROUTE_1650_SEED_SCHEMA_INVALID");
        }
        JsonNode route = object(root, "route");
        JsonNode version = object(root, "route_version");
        JsonNode stopNodes = root.get("route_stops");
        if (stopNodes == null || !stopNodes.isArray()) {
            throw new MigrationException("ROUTE_1650_SEED_STOPS_INVALID");
        }
        List<Stop> stops = new ArrayList<>();
        for (JsonNode node : stopNodes) {
            stops.add(new Stop(
                integer(node, "stop_order"),
                text(node, "stop_id"),
                text(node, "name"),
                text(node, "direction"),
                bool(node, "boarding_allowed")));
        }
        RouteSeed seed = new RouteSeed(
            text(route, "public_route_id"),
            text(route, "source_id"),
            text(route, "source_route_id"),
            text(route, "display_name"),
            text(route, "start_stop_name"),
            text(route, "end_stop_name"),
            nullableInteger(version, "turn_sequence"),
            nullableText(version, "up_first_departure_time"),
            nullableText(version, "up_last_departure_time"),
            nullableText(version, "down_first_departure_time"),
            nullableText(version, "down_last_departure_time"),
            text(version, "content_digest"),
            stops,
            Sha256.of(bytes));
        requireMatchesReference(seed, reference);
        return seed;
    }

    private static void requireMatchesReference(
        RouteSeed seed,
        RouteRoster reference
    ) {
        if (!reference.modelRoute().equals(seed.displayName())
            || !reference.sourceRouteId().equals(seed.sourceRouteId())
            || !reference.turnSequence().equals(seed.turnSequence())
            || reference.stations().size() != seed.stops().size()) {
            throw new MigrationException("ROUTE_1650_SEED_REFERENCE_MISMATCH");
        }
        for (Stop stop : seed.stops()) {
            RouteRoster.Station expected = reference.stations().get(stop.stopOrder());
            if (expected == null || !expected.stopId().equals(stop.stopId())
                || expected.boardingAllowed() != stop.boardingAllowed()) {
                throw new MigrationException("ROUTE_1650_SEED_REFERENCE_MISMATCH");
            }
        }
    }

    static String calculateContentDigest(
        int turnSequence,
        List<Stop> stops
    ) {
        MessageDigest digest = newDigest();
        update(digest, Integer.toString(turnSequence));
        for (Stop stop : stops) {
            update(digest, Integer.toString(stop.stopOrder()));
            update(digest, stop.stopId());
            update(digest, stop.name());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(
        MessageDigest digest,
        String value
    ) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode object(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new MigrationException("ROUTE_1650_SEED_FIELD_INVALID");
        }
        return value;
    }

    private static String text(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MigrationException("ROUTE_1650_SEED_FIELD_INVALID");
        }
        return value.stringValue();
    }

    private static String nullableText(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new MigrationException("ROUTE_1650_SEED_FIELD_INVALID");
        }
        return value.isNull() ? null : text(node, field);
    }

    private static int integer(
        JsonNode node,
        String field
    ) {
        Integer value = nullableInteger(node, field);
        if (value == null) {
            throw new MigrationException("ROUTE_1650_SEED_FIELD_INVALID");
        }
        return value;
    }

    private static Integer nullableInteger(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new MigrationException("ROUTE_1650_SEED_FIELD_INVALID");
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new MigrationException("ROUTE_1650_SEED_FIELD_INVALID");
        }
        return value.intValue();
    }

    private static boolean bool(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new MigrationException("ROUTE_1650_SEED_FIELD_INVALID");
        }
        return value.booleanValue();
    }

    record Stop(
        int stopOrder,
        String stopId,
        String name,
        String direction,
        boolean boardingAllowed
    ) {

        Stop {
            if (stopOrder < 1 || stopId == null || stopId.isBlank() || stopId.length() > 20
                || name == null || name.isBlank() || name.length() > 60
                || !List.of("UP", "DOWN").contains(direction)) {
                throw new MigrationException("ROUTE_1650_SEED_STOP_INVALID");
            }
        }
    }
}
