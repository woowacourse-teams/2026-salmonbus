package com.gustler.backend.migration.db;

import com.gustler.backend.migration.archive.ArchiveManifest;
import com.gustler.backend.migration.archive.ArchiveRecord;
import com.gustler.backend.migration.archive.NormalizedObservation;
import com.gustler.backend.migration.archive.RouteRoster;
import com.gustler.backend.migration.archive.ShardWriter;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ArchiveTestFixture {

    static final Instant FIRST_LIVE_3330 = Instant.parse("2026-09-02T10:27:52.390820Z");
    static final Instant FIRST_LIVE_1650 = Instant.parse("2026-09-02T12:49:40Z");
    static final Instant ORIGINAL_3330 = Instant.parse("2026-09-02T10:27:51.330754Z");
    static final Instant ORIGINAL_1650 = Instant.parse("2026-09-02T12:49:33.041299Z");
    static final String SYNTHETIC_VEHICLE = "fixture-vehicle-not-source";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ArchiveTestFixture() {
    }

    static Fixture create(
        Connection connection,
        Path archiveDirectory
    ) throws Exception {
        JsonNode routes = routeDocument();
        RouteData route3330 = routeData(routes.get("204000057"), "204000057", ORIGINAL_3330);
        RouteData route1650 = routeData(routes.get("234000050"), "234000050", ORIGINAL_1650);
        long version3330 = insertRoute(connection, route3330);
        long version1650 = insertRoute(connection, route1650);
        if (version3330 != 1 || version1650 != 2) {
            throw new IllegalStateException("fixture route version identity changed");
        }
        long live3330Batch = insertLiveBatch(connection, version3330, FIRST_LIVE_3330, SYNTHETIC_VEHICLE,
            route3330.stops().get(0), 15, "live-3330");
        insertLiveBatch(connection, version1650, FIRST_LIVE_1650, "fixture-1650-not-source",
            route1650.stops().get(0), 12, "live-1650");

        List<RouteRoster> rosters = List.of(roster(route1650), roster(route3330));
        List<ArchiveRecord> records = List.of(
            record(route3330, Instant.parse("2026-08-14T08:00:00Z"), "a", 20, SYNTHETIC_VEHICLE),
            record(route1650, Instant.parse("2026-08-14T08:01:00Z"), "b", 18, "fixture-1650-not-source"),
            record(route3330, Instant.parse("2026-09-02T10:27:45.315Z"), "c", 20, SYNTHETIC_VEHICLE),
            record(route1650, Instant.parse("2026-09-02T12:49:31.467Z"), "d", 14, "fixture-1650-not-source"),
            record(route3330, ORIGINAL_3330, "e", 19, "fixture-overlap-3330"),
            record(route1650, ORIGINAL_1650, "f", 13, "fixture-overlap-1650"));

        List<ArchiveManifest.Shard> shards;
        try (ShardWriter writer = new ShardWriter(archiveDirectory, 10_000)) {
            records.stream()
                .sorted(java.util.Comparator.comparing(record -> record.batch().responseReceivedAt()))
                .forEach(writer::append);
            shards = writer.shards();
        }
        Map<String, Long> batchesPerRoute = counts(records, true);
        Map<String, Long> observationsPerRoute = counts(records, false);
        Map<String, Long> batchesPerDate = dateCounts(records, true);
        Map<String, Long> observationsPerDate = dateCounts(records, false);
        ArchiveManifest manifest = new ArchiveManifest(
            "BASE", null, "s3-rds-migration-v1", Instant.parse("2026-09-02T13:00:00Z"),
            new ArchiveManifest.Inventory(
                "object-inventory-v1",
                ArchiveManifest.TerminalFreeze.EXPECTED_IMMUTABLE_BASE_INVENTORY_SHA256,
                6, 6, 0, List.of()),
            "normalization-v1.0.0-s3-backfill", true, null, rosters, shards,
            new ArchiveManifest.Summary(
                records.size(), records.size(), 0, 0, 0, 0, 0,
                records.getFirst().batch().responseReceivedAt(),
                records.getLast().batch().responseReceivedAt(),
                batchesPerRoute, observationsPerRoute, batchesPerDate, observationsPerDate, Map.of()));
        manifest.writeTo(archiveDirectory);
        return new Fixture(
            manifest, version3330, version1650, live3330Batch,
            Path.of(ArchiveTestFixture.class.getResource("/route-seed-1650.json").toURI()));
    }

    static ArchiveManifest createTerminalDelta(
        Path archiveDirectory,
        Fixture base,
        String previousManifestSha256
    ) {
        RouteRoster roster = base.manifest().routeReferences().stream()
            .filter(candidate -> "3330".equals(candidate.modelRoute()))
            .findFirst()
            .orElseThrow();
        RouteRoster.Station stop = roster.stations().get(1);
        Instant receivedAt = Instant.parse("2026-09-02T10:27:47Z");
        String digest = "7".repeat(64);
        ArchiveRecord record = new ArchiveRecord(
            new ArchiveRecord.Batch(
                "827325854159", "1.0.0", UUID.nameUUIDFromBytes(digest.getBytes()),
                digest, "s3v1:" + digest, roster.modelRoute(), roster.sourceRouteId(),
                receivedAt.minusMillis(200), receivedAt.minusMillis(100), receivedAt, 1,
                200, 0, "SUCCESS_ROWS", null, 1, 1, 0,
                "normalization-v1.0.0-s3-backfill", "adaptive-kst-v1.2.0", "S3_BACKFILL"),
            List.of(new NormalizedObservation(
                0, SYNTHETIC_VEHICLE, stop.stopOrder(), stop.stopId(), stop.stopOrder(), 2,
                19, null, 2, 1, 1, null)));
        List<ArchiveManifest.Shard> shards;
        try (ShardWriter writer = new ShardWriter(archiveDirectory, 10_000)) {
            writer.append(record);
            shards = writer.shards();
        }
        ArchiveManifest.TerminalFreeze freeze = new ArchiveManifest.TerminalFreeze(
            "1".repeat(64),
            "2".repeat(64),
            ArchiveManifest.TerminalFreeze.EXPECTED_FINAL_INVENTORY_SHA256,
            ArchiveManifest.TerminalFreeze.EXPECTED_TERMINAL_PARTITION_INVENTORY_SHA256,
            ArchiveManifest.TerminalFreeze.EXPECTED_IMMUTABLE_BASE_INVENTORY_SHA256,
            ArchiveManifest.TerminalFreeze.EXPECTED_SOURCE_CLOSURE_SHA256,
            ArchiveManifest.TerminalFreeze.EXPECTED_COLLECTOR_CODE_SHA256_BASE64,
            ArchiveManifest.TerminalFreeze.EXPECTED_DISABLED_AT,
            Instant.parse("2026-09-02T13:22:53.278Z"));
        ArchiveManifest manifest = new ArchiveManifest(
            "TERMINAL_DELTA",
            previousManifestSha256,
            "s3-rds-migration-v1",
            Instant.parse("2026-09-02T13:23:40.988Z"),
            new ArchiveManifest.Inventory(
                "object-inventory-v1",
                ArchiveManifest.TerminalFreeze.EXPECTED_FINAL_INVENTORY_SHA256,
                1, 1, 0, List.of()),
            "normalization-v1.0.0-s3-backfill",
            true,
            freeze,
            base.manifest().routeReferences(),
            shards,
            new ArchiveManifest.Summary(
                1, 1, 0, 0, 0, 0, 0, receivedAt, receivedAt,
                Map.of("3330", 1L), Map.of("3330", 1L),
                Map.of("2026-09-02", 1L), Map.of("2026-09-02", 1L), Map.of()));
        manifest.writeTo(archiveDirectory);
        return manifest;
    }

    private static ArchiveRecord record(
        RouteData route,
        Instant receivedAt,
        String digestCharacter,
        int seats,
        String vehicleId
    ) {
        String digest = digestCharacter.repeat(64);
        StopData stop = route.stops().get(0);
        return new ArchiveRecord(
            new ArchiveRecord.Batch(
                "827325854159", "1.0.0", UUID.nameUUIDFromBytes(digest.getBytes()),
                digest, "s3v1:" + digest, route.displayName(), route.publicRouteId(),
                receivedAt.minusMillis(200), receivedAt.minusMillis(100), receivedAt, 1,
                200, 0, "SUCCESS_ROWS", null, 1, 1, 0,
                "normalization-v1.0.0-s3-backfill", "adaptive-kst-v1.2.0", "S3_BACKFILL"),
            List.of(new NormalizedObservation(
                0, vehicleId, stop.order(), stop.id(), stop.order(), 2,
                seats, null, 2, 1, 1, null)));
    }

    private static RouteRoster roster(RouteData route) {
        Map<Integer, RouteRoster.Station> stations = new TreeMap<>();
        for (StopData stop : route.stops()) {
            stations.put(stop.order(), new RouteRoster.Station(
                stop.order(), stop.id(), stop.boardingAllowed()));
        }
        return new RouteRoster(
            "gbis-2026-08-19", LocalDate.parse("2026-01-01"), null,
            route.displayName(), route.publicRouteId(), route.turnSequence(), stations, null);
    }

    private static long insertRoute(Connection connection, RouteData route) throws Exception {
        long routeId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route (
                public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
            VALUES (?, 'GBIS', ?, ?, ?, ?) RETURNING id
            """)) {
            statement.setString(1, route.publicRouteId());
            statement.setString(2, route.publicRouteId());
            statement.setString(3, route.displayName());
            statement.setString(4, route.startStopName());
            statement.setString(5, route.endStopName());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                routeId = rows.getLong(1);
            }
        }
        long versionId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route_version (
                route_id, turn_sequence, up_first_departure_time, up_last_departure_time,
                down_first_departure_time, down_last_departure_time, content_digest, valid_from)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """)) {
            statement.setLong(1, routeId);
            statement.setInt(2, route.turnSequence());
            statement.setString(3, route.upFirst());
            statement.setString(4, route.upLast());
            statement.setString(5, route.downFirst());
            statement.setString(6, route.downLast());
            statement.setString(7, route.contentDigest());
            statement.setObject(8, route.originalValidFrom().atOffset(java.time.ZoneOffset.UTC));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                versionId = rows.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route_stop (
                route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            for (StopData stop : route.stops()) {
                statement.setLong(1, versionId);
                statement.setInt(2, stop.order());
                statement.setString(3, stop.id());
                statement.setString(4, stop.name());
                statement.setString(5, stop.direction());
                statement.setBoolean(6, stop.boardingAllowed());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return versionId;
    }

    private static long insertLiveBatch(
        Connection connection,
        long versionId,
        Instant receivedAt,
        String vehicleId,
        StopData stop,
        int seats,
        String attempt
    ) throws Exception {
        long batchId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, outcome, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version)
            VALUES (?, ?, 1, ?, ?, ?, 'SUCCESS_ROWS', 1, 1, 0,
                    'normalization-v1.0.0', 'adaptive-kst-v1.0.1') RETURNING id
            """)) {
            statement.setLong(1, versionId);
            statement.setObject(2, receivedAt.minusSeconds(1).atOffset(java.time.ZoneOffset.UTC));
            statement.setString(3, attempt);
            statement.setObject(4, receivedAt.minusMillis(500).atOffset(java.time.ZoneOffset.UTC));
            statement.setObject(5, receivedAt.atOffset(java.time.ZoneOffset.UTC));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                batchId = rows.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO vehicle_observation (
                observation_batch_id, route_version_id, source_row_number, vehicle_id,
                stop_order, stop_id, passed_stop_order, running_state, remaining_seats,
                crowd_level, vehicle_type, route_type, tagless)
            VALUES (?, ?, 0, ?, ?, ?, ?, 2, ?, 2, 1, 1, NULL)
            """)) {
            statement.setLong(1, batchId);
            statement.setLong(2, versionId);
            statement.setString(3, vehicleId);
            statement.setInt(4, stop.order() + 1);
            statement.setString(5, routeStopId(connection, versionId, stop.order() + 1));
            statement.setInt(6, stop.order() + 1);
            statement.setInt(7, seats);
            statement.executeUpdate();
        }
        return batchId;
    }

    private static String routeStopId(Connection connection, long versionId, int order) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT stop_id FROM route_stop WHERE route_version_id = ? AND stop_order = ?
            """)) {
            statement.setLong(1, versionId);
            statement.setInt(2, order);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static RouteData routeData(JsonNode node, String publicRouteId, Instant validFrom) {
        List<StopData> stops = new ArrayList<>();
        for (JsonNode stop : node.get("stops")) {
            stops.add(new StopData(
                stop.get("sequence").intValue(), stop.get("stationId").asString(),
                stop.get("name").asString(), stop.get("direction").asString(),
                stop.get("boardingAllowed").booleanValue()));
        }
        Map<String, JsonNode> directions = new java.util.HashMap<>();
        for (JsonNode direction : node.get("directions")) {
            directions.put(direction.get("id").asString(), direction);
        }
        List<RouteSeed.Stop> digestStops = stops.stream()
            .map(stop -> new RouteSeed.Stop(
                stop.order(), stop.id(), stop.name(), stop.direction(), stop.boardingAllowed()))
            .toList();
        int turn = node.get("turnSequence").intValue();
        return new RouteData(
            publicRouteId, node.get("displayName").asString(), node.get("startStopName").asString(),
            node.get("endStopName").asString(), turn,
            directions.get("UP").get("firstDepartureTime").asString(),
            directions.get("UP").get("lastDepartureTime").asString(),
            directions.get("DOWN").get("firstDepartureTime").asString(),
            directions.get("DOWN").get("lastDepartureTime").asString(),
            RouteSeed.calculateContentDigest(turn, digestStops), validFrom, stops);
    }

    private static JsonNode routeDocument() throws Exception {
        try (InputStream input = ArchiveTestFixture.class.getResourceAsStream("/routes.json")) {
            return JSON.readTree(input);
        }
    }

    private static Map<String, Long> counts(List<ArchiveRecord> records, boolean batches) {
        Map<String, Long> result = new TreeMap<>();
        for (ArchiveRecord record : records) {
            result.merge(record.batch().routeName(), batches ? 1L : (long) record.observations().size(), Long::sum);
        }
        return result;
    }

    private static Map<String, Long> dateCounts(List<ArchiveRecord> records, boolean batches) {
        Map<String, Long> result = new TreeMap<>();
        for (ArchiveRecord record : records) {
            String date = record.batch().responseReceivedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
            result.merge(date, batches ? 1L : (long) record.observations().size(), Long::sum);
        }
        return result;
    }

    record Fixture(
        ArchiveManifest manifest,
        long version3330,
        long version1650,
        long live3330Batch,
        Path routeSeed1650
    ) {
    }

    record RouteData(
        String publicRouteId,
        String displayName,
        String startStopName,
        String endStopName,
        int turnSequence,
        String upFirst,
        String upLast,
        String downFirst,
        String downLast,
        String contentDigest,
        Instant originalValidFrom,
        List<StopData> stops
    ) {
    }

    record StopData(int order, String id, String name, String direction, boolean boardingAllowed) {
    }
}
