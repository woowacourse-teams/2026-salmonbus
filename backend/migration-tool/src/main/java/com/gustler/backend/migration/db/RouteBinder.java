package com.gustler.backend.migration.db;

import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.archive.ArchiveManifest;
import com.gustler.backend.migration.archive.RouteRoster;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** 두 current route version을 exact content gate 뒤 accepted source의 첫 instant까지만 확장한다. */
final class RouteBinder {

    private static final Map<String, ExpectedRoute> EXPECTED = Map.of(
        "3330", new ExpectedRoute(
            1, "204000057", 43, 85, 7,
            "91749006e76e5f822c1c2e241b37fae4eba6941e217dcba2928c6e7e8ffdae5d",
            Instant.parse("2026-09-02T10:27:51.330754Z")),
        "1650", new ExpectedRoute(
            2, "234000050", 44, 89, 24,
            "f6b5a3b0fabd731dbbab36054826be26714061c42d66b862eac60b191b4b51bc",
            Instant.parse("2026-09-02T12:49:33.041299Z")));

    Map<String, Long> preflight(
        Connection connection,
        ImportSettings settings,
        ArchiveManifest manifest
    ) throws SQLException {
        Map<String, Long> versions = new TreeMap<>();
        for (Map.Entry<String, ExpectedRoute> entry : new TreeMap<>(EXPECTED).entrySet()) {
            String modelRoute = entry.getKey();
            ExpectedRoute expected = entry.getValue();
            List<RouteRoster> rosters = manifest.routeReferences().stream()
                .filter(roster -> modelRoute.equals(roster.modelRoute()))
                .filter(roster -> expected.sourceRouteId().equals(roster.sourceRouteId()))
                .toList();
            if (rosters.size() != 1) {
                throw new MigrationException("ARCHIVE_ROUTE_ROSTER_PREFLIGHT_NOT_UNIQUE");
            }
            RouteRoster reference = rosters.getFirst();
            StagedRoute staged = new StagedRoute(
                modelRoute, expected.sourceRouteId(), reference.version(),
                manifest.summary().collectedFrom(), manifest.summary().collectedThrough());
            CurrentRoute current = requireExactCurrentRoute(connection, staged, expected);
            requireKnownCurrentValidity(connection, current, expected);
            FullRoute full = readFullRoute(connection, current);
            requireReferenceMatch(reference, full, expected);
            if ("1650".equals(modelRoute)) {
                RouteSeed seed = RouteSeed.read(settings.routeSeed1650File(), reference);
                requireMatchesSeed(full, seed);
            }
            versions.put(modelRoute, current.versionId());
        }
        return Map.copyOf(versions);
    }

    List<Binding> bind(
        Connection connection,
        ImportSettings settings,
        ArchiveManifest manifest,
        UUID importId
    ) throws SQLException {
        if (settings.routeValidityPolicy()
            != ImportSettings.RouteValidityPolicy.EXTEND_EXACT_CURRENT_VERSION) {
            throw new MigrationException("ROUTE_MAPPING_POLICY_INVALID");
        }
        List<Binding> bindings = new ArrayList<>();
        for (StagedRoute staged : stagedRoutes(connection, importId)) {
            ExpectedRoute expected = EXPECTED.get(staged.modelRoute());
            if (expected == null || !expected.sourceRouteId().equals(staged.sourceRouteId())) {
                throw new MigrationException("ROUTE_MAPPING_UNSUPPORTED_ROUTE");
            }
            RouteRoster reference = uniqueRoster(manifest, staged);
            CurrentRoute current = requireExactCurrentRoute(connection, staged, expected);
            requireKnownCurrentValidity(connection, current, expected);
            FullRoute full = readFullRoute(connection, current);
            requireReferenceMatch(reference, full, expected);
            if ("1650".equals(staged.modelRoute())) {
                RouteSeed seed = RouteSeed.read(settings.routeSeed1650File(), reference);
                requireMatchesSeed(full, seed);
            }
            Binding binding = extendOrReuse(connection, importId, staged, reference, current, full);
            insertBinding(connection, importId, binding);
            requireEveryAcceptedInstantCovered(connection, importId, binding);
            bindings.add(binding);
        }
        return List.copyOf(bindings);
    }

    private static List<StagedRoute> stagedRoutes(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT staged.model_route, staged.source_route_id, staged.route_reference_version,
                   MIN(staged.response_received_at) AS first_response,
                   MAX(staged.response_received_at) AS last_response
            FROM historical_import_stage_batch staged
            JOIN historical_import_route_boundary boundary
              ON boundary.import_batch_id = staged.import_batch_id
             AND boundary.model_route = staged.model_route
            WHERE staged.import_batch_id = ?
              AND staged.response_received_at < boundary.target_authority_from
            GROUP BY staged.model_route, staged.source_route_id, staged.route_reference_version
            ORDER BY staged.model_route
            """)) {
            statement.setObject(1, importId);
            List<StagedRoute> routes = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    routes.add(new StagedRoute(
                        rows.getString("model_route"),
                        rows.getString("source_route_id"),
                        rows.getString("route_reference_version"),
                        rows.getObject("first_response", OffsetDateTime.class).toInstant(),
                        rows.getObject("last_response", OffsetDateTime.class).toInstant()));
                }
            }
            if (routes.stream().map(StagedRoute::modelRoute).distinct().count() != routes.size()) {
                throw new MigrationException("MULTIPLE_ROUTE_ROSTERS_REQUIRE_SEPARATE_IMPORTS");
            }
            return routes;
        }
    }

    private static RouteRoster uniqueRoster(
        ArchiveManifest manifest,
        StagedRoute staged
    ) {
        List<RouteRoster> matches = manifest.routeReferences().stream()
            .filter(roster -> roster.modelRoute().equals(staged.modelRoute()))
            .filter(roster -> roster.sourceRouteId().equals(staged.sourceRouteId()))
            .filter(roster -> roster.version().equals(staged.routeReferenceVersion()))
            .toList();
        if (matches.size() != 1) {
            throw new MigrationException("ARCHIVE_ROUTE_ROSTER_BINDING_NOT_UNIQUE");
        }
        return matches.getFirst();
    }

    private static CurrentRoute requireExactCurrentRoute(
        Connection connection,
        StagedRoute staged,
        ExpectedRoute expected
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT route.id AS route_id, version.id AS version_id, version.turn_sequence,
                   version.content_digest, version.valid_from, version.valid_to,
                   version.up_first_departure_time, version.up_last_departure_time,
                   version.down_first_departure_time, version.down_last_departure_time,
                   (SELECT count(*) FROM route_version all_versions
                    WHERE all_versions.route_id = route.id) AS version_count
            FROM route
            JOIN route_version version ON version.route_id = route.id
            WHERE route.public_route_id = ? AND route.source_id = 'GBIS'
              AND route.source_route_id = ? AND route.display_name = ?
              AND version.id = ?
            """)) {
            statement.setString(1, expected.sourceRouteId());
            statement.setString(2, expected.sourceRouteId());
            statement.setString(3, staged.modelRoute());
            statement.setLong(4, expected.versionId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("DATABASE_EXACT_CURRENT_ROUTE_MISSING");
                }
                OffsetDateTime validTo = rows.getObject("valid_to", OffsetDateTime.class);
                if (rows.getLong("version_count") != 1 || validTo != null
                    || rows.getObject("turn_sequence", Integer.class) != expected.turnSequence()
                    || !expected.contentDigest().equals(rows.getString("content_digest"))) {
                    throw new MigrationException("DATABASE_EXACT_CURRENT_ROUTE_PRECONDITION_FAILED");
                }
                CurrentRoute current = new CurrentRoute(
                    rows.getLong("route_id"), rows.getLong("version_id"),
                    rows.getObject("turn_sequence", Integer.class), rows.getString("content_digest"),
                    rows.getObject("valid_from", OffsetDateTime.class).toInstant(),
                    rows.getString("up_first_departure_time"), rows.getString("up_last_departure_time"),
                    rows.getString("down_first_departure_time"), rows.getString("down_last_departure_time"));
                if (rows.next()) {
                    throw new MigrationException("DATABASE_EXACT_CURRENT_ROUTE_NOT_UNIQUE");
                }
                return current;
            }
        }
    }

    private static FullRoute readFullRoute(
        Connection connection,
        CurrentRoute route
    ) throws SQLException {
        List<RouteSeed.Stop> stops = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT stop_order, stop_id, name, direction, boarding_allowed
            FROM route_stop WHERE route_version_id = ? ORDER BY stop_order
            """)) {
            statement.setLong(1, route.versionId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    stops.add(new RouteSeed.Stop(
                        rows.getInt("stop_order"), rows.getString("stop_id"), rows.getString("name"),
                        rows.getString("direction"), rows.getBoolean("boarding_allowed")));
                }
            }
        }
        String digest = RouteSeed.calculateContentDigest(route.turnSequence(), stops);
        if (!digest.equals(route.contentDigest())) {
            throw new MigrationException("DATABASE_ROUTE_CONTENT_DIGEST_MISMATCH");
        }
        return new FullRoute(route, stops);
    }

    private static void requireReferenceMatch(
        RouteRoster reference,
        FullRoute full,
        ExpectedRoute expected
    ) {
        if (!reference.turnSequence().equals(full.route().turnSequence())
            || full.stops().size() != expected.stopCount()
            || full.stops().stream().filter(stop -> !stop.boardingAllowed()).count()
                != expected.nonBoardingCount()) {
            throw new MigrationException("DATABASE_ROUTE_ROSTER_MISMATCH");
        }
        for (RouteSeed.Stop stop : full.stops()) {
            RouteRoster.Station wanted = reference.stations().get(stop.stopOrder());
            if (wanted == null || !wanted.stopId().equals(stop.stopId())
                || wanted.boardingAllowed() != stop.boardingAllowed()) {
                throw new MigrationException("DATABASE_ROUTE_ROSTER_MISMATCH");
            }
        }
    }

    private static void requireMatchesSeed(
        FullRoute full,
        RouteSeed seed
    ) {
        CurrentRoute route = full.route();
        if (!route.contentDigest().equals(seed.contentDigest())
            || !route.turnSequence().equals(seed.turnSequence())
            || !java.util.Objects.equals(route.upFirstDepartureTime(), seed.upFirstDepartureTime())
            || !java.util.Objects.equals(route.upLastDepartureTime(), seed.upLastDepartureTime())
            || !java.util.Objects.equals(route.downFirstDepartureTime(), seed.downFirstDepartureTime())
            || !java.util.Objects.equals(route.downLastDepartureTime(), seed.downLastDepartureTime())
            || !full.stops().equals(seed.stops())) {
            throw new MigrationException("DATABASE_ROUTE_1650_SEED_MISMATCH");
        }
    }

    private static Binding extendOrReuse(
        Connection connection,
        UUID importId,
        StagedRoute staged,
        RouteRoster reference,
        CurrentRoute current,
        FullRoute full
    ) throws SQLException {
        if (current.validFrom().isAfter(staged.firstResponse())) {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE route_version SET valid_from = ?
                WHERE id = ? AND valid_to IS NULL AND valid_from = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM route_version other
                    WHERE other.route_id = ? AND other.id <> ?)
                """)) {
                statement.setObject(1, offset(staged.firstResponse()));
                statement.setLong(2, current.versionId());
                statement.setObject(3, offset(current.validFrom()));
                statement.setLong(4, current.routeId());
                statement.setLong(5, current.versionId());
                if (statement.executeUpdate() != 1) {
                    throw new MigrationException("ROUTE_VALID_FROM_EXTENSION_CONCURRENT_CONFLICT");
                }
            }
            return binding(staged, reference, full, current.validFrom(), staged.firstResponse(),
                "EXTENDED_CURRENT_ROUTE");
        }
        requireEarlierValidityProvenance(connection, importId, current.versionId(), current.validFrom());
        return binding(staged, reference, full, null, current.validFrom(), "REUSED_EXACT_VERSION");
    }

    private static void requireKnownCurrentValidity(
        Connection connection,
        CurrentRoute current,
        ExpectedRoute expected
    ) throws SQLException {
        if (current.validFrom().equals(expected.originalValidFrom())) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1 FROM historical_import_route_binding binding
                JOIN historical_import_batch imported ON imported.id = binding.import_batch_id
                WHERE binding.route_version_id = ?
                  AND binding.valid_from = ?
                  AND binding.original_valid_from = ?
                  AND imported.status IN ('VALIDATED', 'MERGING', 'COMPLETE'))
            """)) {
            statement.setLong(1, current.versionId());
            statement.setObject(2, offset(current.validFrom()));
            statement.setObject(3, offset(expected.originalValidFrom()));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (!rows.getBoolean(1)) {
                    throw new MigrationException("ROUTE_CURRENT_VALID_FROM_UNEXPLAINED");
                }
            }
        }
    }

    private static void requireEarlierValidityProvenance(
        Connection connection,
        UUID importId,
        long routeVersionId,
        Instant validFrom
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1
                FROM historical_import_route_binding binding
                JOIN historical_import_batch imported ON imported.id = binding.import_batch_id
                WHERE binding.route_version_id = ? AND binding.valid_from = ?
                  AND imported.status IN ('VALIDATED', 'MERGING', 'COMPLETE'))
            """)) {
            statement.setLong(1, routeVersionId);
            statement.setObject(2, offset(validFrom));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (!rows.getBoolean(1)) {
                    throw new MigrationException("ROUTE_EARLIER_VALID_FROM_HAS_NO_IMPORT_PROVENANCE");
                }
            }
        }
    }

    private static Binding binding(
        StagedRoute staged,
        RouteRoster reference,
        FullRoute full,
        Instant originalValidFrom,
        Instant validFrom,
        String kind
    ) {
        Map<Integer, RouteRoster.Station> stations = new TreeMap<>();
        for (RouteSeed.Stop stop : full.stops()) {
            stations.put(stop.stopOrder(), new RouteRoster.Station(
                stop.stopOrder(), stop.stopId(), stop.boardingAllowed()));
        }
        RouteRoster databaseRoster = new RouteRoster(
            reference.version(), reference.effectiveFrom(), reference.effectiveThrough(),
            reference.modelRoute(), reference.sourceRouteId(), full.route().turnSequence(), stations, null);
        return new Binding(
            staged.modelRoute(), staged.sourceRouteId(), reference.version(), reference.rosterSha256(),
            databaseRoster.rosterSha256(), full.route().versionId(), originalValidFrom,
            validFrom, null, full.route().contentDigest(), kind);
    }

    private static void insertBinding(
        Connection connection,
        UUID importId,
        Binding binding
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO historical_import_route_binding (
                import_batch_id, model_route, source_route_id, route_reference_version,
                archive_roster_sha256, database_roster_sha256, route_version_id,
                original_valid_from, valid_from, valid_to, mapping_kind)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (import_batch_id, model_route) DO NOTHING
            """)) {
            statement.setObject(1, importId);
            statement.setString(2, binding.modelRoute());
            statement.setString(3, binding.sourceRouteId());
            statement.setString(4, binding.routeReferenceVersion());
            statement.setString(5, binding.archiveRosterSha256());
            statement.setString(6, binding.databaseRosterSha256());
            statement.setLong(7, binding.routeVersionId());
            nullableTime(statement, 8, binding.originalValidFrom());
            statement.setObject(9, offset(binding.validFrom()));
            nullableTime(statement, 10, binding.validTo());
            statement.setString(11, binding.mappingKind());
            statement.executeUpdate();
        }
    }

    /** 지금 정책에서는 validFrom이 이 모집단의 MIN이라 구성상 항상 참이다. 다른 정책이 생길 때를 위한 가드다. */
    private static void requireEveryAcceptedInstantCovered(
        Connection connection,
        UUID importId,
        Binding binding
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*)
            FROM historical_import_stage_batch staged
            JOIN historical_import_route_boundary boundary
              ON boundary.import_batch_id = staged.import_batch_id
             AND boundary.model_route = staged.model_route
            WHERE staged.import_batch_id = ? AND staged.model_route = ?
              AND staged.response_received_at < boundary.target_authority_from
              AND NOT (
                  staged.response_received_at >= ?
                  AND (CAST(? AS timestamptz) IS NULL
                       OR staged.response_received_at < CAST(? AS timestamptz)))
            """)) {
            statement.setObject(1, importId);
            statement.setString(2, binding.modelRoute());
            statement.setObject(3, offset(binding.validFrom()));
            nullableTime(statement, 4, binding.validTo());
            nullableTime(statement, 5, binding.validTo());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != 0) {
                    throw new MigrationException("ROUTE_VERSION_DOES_NOT_COVER_EVERY_RESPONSE_INSTANT");
                }
            }
        }
    }

    private static OffsetDateTime offset(
        Instant instant
    ) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static void nullableTime(
        PreparedStatement statement,
        int index,
        Instant instant
    ) throws SQLException {
        if (instant == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setObject(index, offset(instant));
        }
    }

    record Binding(
        String modelRoute,
        String sourceRouteId,
        String routeReferenceVersion,
        String archiveRosterSha256,
        String databaseRosterSha256,
        long routeVersionId,
        Instant originalValidFrom,
        Instant validFrom,
        Instant validTo,
        String contentDigest,
        String mappingKind
    ) {
    }

    private record StagedRoute(
        String modelRoute,
        String sourceRouteId,
        String routeReferenceVersion,
        Instant firstResponse,
        Instant lastResponse
    ) {
    }

    private record ExpectedRoute(
        long versionId,
        String sourceRouteId,
        int turnSequence,
        int stopCount,
        int nonBoardingCount,
        String contentDigest,
        Instant originalValidFrom
    ) {
    }

    private record CurrentRoute(
        long routeId,
        long versionId,
        Integer turnSequence,
        String contentDigest,
        Instant validFrom,
        String upFirstDepartureTime,
        String upLastDepartureTime,
        String downFirstDepartureTime,
        String downLastDepartureTime
    ) {
    }

    private record FullRoute(
        CurrentRoute route,
        List<RouteSeed.Stop> stops
    ) {

        private FullRoute {
            stops = List.copyOf(stops);
        }
    }
}
