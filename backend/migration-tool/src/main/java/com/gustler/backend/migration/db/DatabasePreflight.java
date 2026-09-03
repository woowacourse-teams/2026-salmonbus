package com.gustler.backend.migration.db;

import com.gustler.backend.migration.DatabaseEnvironment;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.archive.ArchiveVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.Map;

public final class DatabasePreflight {

    private static final Set<String> REQUIRED_TABLES = Set.of(
        "daily_call_quota", "flyway_schema_history", "model_deployment", "observation_batch",
        "route", "route_stop", "route_version", "seat_forecast", "stop_demand_statistics",
        "vehicle_observation");
    private static final long ESTIMATED_BYTES_PER_BATCH_PEAK = 4_096;
    private static final long ESTIMATED_BYTES_PER_OBSERVATION_PEAK = 2_048;

    public Result check(
        ImportSettings settings,
        ArchiveVerifier.Verification archive,
        boolean writeRequired
    ) {
        if (!archive.manifest().complete()) {
            throw new MigrationException("ARCHIVE_INCOMPLETE_DATES_OR_REJECTS");
        }
        requireLocalDisk(settings, archive);
        long estimatedPeak = estimatedDatabasePeakBytes(archive);
        if (settings.database().targetKind() == DatabaseEnvironment.TargetKind.ACADEMY
            && (settings.confirmedDatabaseFreeBytes() == 0
                || estimatedPeak > settings.confirmedDatabaseFreeBytes() / 2)) {
            throw new MigrationException("DATABASE_HEADROOM_NOT_CONFIRMED");
        }
        return DatabaseConnections.transaction(settings.database(), connection -> {
            if (!writeRequired) {
                SqlSafety.setTransactionReadOnly(connection);
            }
            SqlSafety.setLocalTimeouts(connection, settings);
            requireTables(connection);
            Map<String, Long> routeVersions =
                new RouteBinder().preflight(connection, settings, archive.manifest());
            boolean readOnly = transactionReadOnly(connection);
            if (writeRequired && readOnly) {
                throw new MigrationException("DATABASE_IS_READ_ONLY");
            }
            if (!writeRequired && !readOnly) {
                throw new MigrationException("DATABASE_READ_ONLY_PREFLIGHT_NOT_ENFORCED");
            }
            Map<String, Instant> currentValidity = currentRouteValidity(connection);
            requireTargetAuthorityProvenance(
                connection, currentValidity, settings.targetAuthorityFrom());
            Map<String, Instant> firstLive = firstLiveObservations(connection);
            return new Result(
                estimatedPeak, routeVersions, settings.targetAuthorityFrom(),
                currentValidity, firstLive, readOnly);
        });
    }

    private static void requireLocalDisk(
        ImportSettings settings,
        ArchiveVerifier.Verification archive
    ) {
        try {
            long usable = Files.getFileStore(settings.archiveDirectory()).getUsableSpace();
            long archiveBytes = archive.manifest().shards().stream()
                .mapToLong(shard -> shard.compressedBytes()).sum();
            if (usable < settings.minimumLocalFreeBytes() + archiveBytes) {
                throw new MigrationException("LOCAL_DISK_HEADROOM_INSUFFICIENT");
            }
        } catch (IOException e) {
            throw new MigrationException("LOCAL_DISK_HEADROOM_UNREADABLE", e);
        }
    }

    private static long estimatedDatabasePeakBytes(
        ArchiveVerifier.Verification archive
    ) {
        try {
            return Math.addExact(
                Math.multiplyExact(archive.batchCount(), ESTIMATED_BYTES_PER_BATCH_PEAK),
                Math.multiplyExact(archive.observationCount(), ESTIMATED_BYTES_PER_OBSERVATION_PEAK));
        } catch (ArithmeticException e) {
            throw new MigrationException("DATABASE_SPACE_ESTIMATE_OVERFLOW", e);
        }
    }

    private static void requireTables(
        Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
            """); ResultSet rows = statement.executeQuery()) {
            Set<String> actual = new java.util.HashSet<>();
            while (rows.next()) {
                actual.add(rows.getString(1));
            }
            if (!actual.containsAll(REQUIRED_TABLES)) {
                throw new MigrationException("DATABASE_REQUIRED_TABLE_MISSING");
            }
        }
    }

    private static boolean transactionReadOnly(
        Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SHOW transaction_read_only");
            ResultSet rows = statement.executeQuery()) {
            rows.next();
            return "on".equals(rows.getString(1));
        }
    }

    private static Map<String, Instant> currentRouteValidity(
        Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT route.display_name, version.valid_from
            FROM route
            JOIN route_version version ON version.route_id = route.id
            WHERE route.display_name IN ('1650', '3330') AND version.valid_to IS NULL
            """); ResultSet rows = statement.executeQuery()) {
            Map<String, Instant> result = new java.util.HashMap<>();
            while (rows.next()) {
                String route = rows.getString(1);
                Instant previous = result.put(
                    route, rows.getObject(2, java.time.OffsetDateTime.class).toInstant());
                if (previous != null) {
                    throw new MigrationException("CURRENT_ROUTE_AUTHORITY_NOT_UNIQUE");
                }
            }
            return Map.copyOf(result);
        }
    }

    private static void requireTargetAuthorityProvenance(
        Connection connection,
        Map<String, Instant> currentValidity,
        Map<String, Instant> targetAuthority
    ) throws SQLException {
        if (!currentValidity.keySet().equals(targetAuthority.keySet())) {
            throw new MigrationException("CURRENT_ROUTE_AUTHORITY_MISMATCH");
        }
        for (Map.Entry<String, Instant> expected : targetAuthority.entrySet()) {
            Instant current = currentValidity.get(expected.getKey());
            if (current.equals(expected.getValue())) {
                continue;
            }
            if (current.isAfter(expected.getValue())
                || !hasEarlierValidityProvenance(
                    connection, expected.getKey(), current, expected.getValue())) {
                throw new MigrationException("CURRENT_ROUTE_AUTHORITY_MISMATCH");
            }
        }
    }

    private static boolean hasEarlierValidityProvenance(
        Connection connection,
        String modelRoute,
        Instant currentValidFrom,
        Instant originalValidFrom
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1
                FROM historical_import_route_binding binding
                JOIN historical_import_batch imported ON imported.id = binding.import_batch_id
                JOIN route_version version ON version.id = binding.route_version_id
                JOIN route ON route.id = version.route_id
                WHERE route.display_name = ?
                  AND binding.valid_from = ?
                  AND binding.original_valid_from = ?
                  AND imported.status IN ('VALIDATED', 'MERGING', 'COMPLETE'))
            """)) {
            statement.setString(1, modelRoute);
            statement.setObject(2, currentValidFrom.atOffset(java.time.ZoneOffset.UTC));
            statement.setObject(3, originalValidFrom.atOffset(java.time.ZoneOffset.UTC));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static Map<String, Instant> firstLiveObservations(
        Connection connection
    ) throws SQLException {
        boolean originColumn = hasColumn(connection, "observation_batch", "ingestion_origin");
        String predicate = originColumn ? "AND batch.ingestion_origin = 'LIVE'" : "";
        try (PreparedStatement statement = connection.prepareStatement(
            """
            SELECT route.display_name, MIN(batch.response_received_at)
            FROM observation_batch batch
            JOIN route_version version ON version.id = batch.route_version_id
            JOIN route ON route.id = version.route_id
            WHERE batch.response_received_at IS NOT NULL
              AND route.display_name IN ('1650', '3330')
            """ + predicate + " GROUP BY route.display_name");
            ResultSet rows = statement.executeQuery()) {
            Map<String, Instant> result = new java.util.HashMap<>();
            while (rows.next()) {
                result.put(rows.getString(1), rows.getObject(2, java.time.OffsetDateTime.class).toInstant());
            }
            return Map.copyOf(result);
        }
    }

    private static boolean hasColumn(
        Connection connection,
        String table,
        String column
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?)
            """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    public record Result(
        long estimatedPeakDatabaseBytes,
        Map<String, Long> currentRouteVersionIdByRoute,
        Map<String, Instant> targetAuthorityFromByRoute,
        Map<String, Instant> currentRouteValidFromByRoute,
        Map<String, Instant> firstLiveObservationAtByRoute,
        boolean readOnly
    ) {
    }
}
