package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RdsObservationFullReplayIntegrationTest extends PostgresMigrationTestSupport {

    private static final Instant CUTOFF_1650 = Instant.parse("2026-09-02T12:49:33.041299Z");
    private static final Instant CUTOFF_3330 = Instant.parse("2026-09-02T10:27:52.390820Z");
    private static final Instant FINAL_CUTOVER = Instant.parse("2026-09-02T14:30:00Z");

    @BeforeAll
    static void migrateHistoricalSchema() {
        new HistoricalSchema().migrate(database);
    }

    @Test
    void fullReplayMatchesSourceAtCutoffAndIncludesCrossingNewAndCapacityRestatedKeys() throws Exception {
        Map<String, Long> versions;
        long highWater;
        try (Connection connection = connection()) {
            UUID importId = insertImport(connection);
            versions = Map.of(
                "1650", insertRoute(connection, "1650", "910000010", "3"),
                "3330", insertRoute(connection, "3330", "910000020", "4"));
            seedHistory(connection, versions.get("1650"), CUTOFF_1650, "1650", importId);
            seedHistory(connection, versions.get("3330"), CUTOFF_3330, "3330", importId);
            insertObservation(connection, versions.get("3330"), FINAL_CUTOVER,
                "upper-bound-exclusive", "3330-boundary", 1, 30, "LIVE", null);
            highWater = scalar(connection, "SELECT max(id) FROM observation_batch");
            insertObservation(connection, versions.get("3330"), FINAL_CUTOVER.minusSeconds(120),
                "after-high-water-prediction", "3330-after-high-water", 1, 30, "LIVE", null);
            insertObservation(connection, versions.get("3330"), FINAL_CUTOVER.minusSeconds(30),
                "after-high-water-arrival", "3330-after-high-water", 2, 20, "LIVE", null);
        }
        List<SeedHourlyRow> sourceRows = sourceRows();
        AggregateSeedPayload source = new AggregateSeedPayload(
            "a".repeat(64), "b".repeat(64), "c".repeat(64),
            SeedRows.canonicalRowsSha256(sourceRows), SeedRows.primaryKeySha256(sourceRows),
            "observed-max-capacity-v1", 60, 60,
            Map.of("1650", CUTOFF_1650, "3330", CUTOFF_3330),
            sourceRows, SeedRows.aggregateSet(sourceRows));
        TemporaryReleaseMaintenance.CutoverBoundary boundary =
            new TemporaryReleaseMaintenance.CutoverBoundary(FINAL_CUTOVER, highWater);

        RdsObservationDeltaBuilder.Delta delta = DatabaseConnections.transaction(
            database, connection -> {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);
                SqlSafety.setTransactionReadOnly(connection);
                return new RdsObservationDeltaBuilder().build(connection, source, boundary);
            });

        assertThat(delta.sourceCutoffReplayCanonicalRowsSha256())
            .isEqualTo(source.canonicalRowsSha256());
        assertThat(delta.changedKeyCount()).isGreaterThanOrEqualTo(4);
        assertThat(delta.newKeyCount()).isGreaterThanOrEqualTo(2);
        assertThat(delta.capacityRestatedExistingKeyCount()).isGreaterThanOrEqualTo(2);
        assertThat(delta.routeCounts().values())
            .allSatisfy(counts -> assertThat(counts.crossBoundarySettledTargets()).isPositive());
        assertThat(delta.routeCounts().get("3330").observations()).isEqualTo(7);
        assertThat(delta.finalRows()).hasSizeGreaterThan(sourceRows.size());
        assertThat(delta.receipt().get("transactionIsolation"))
            .isEqualTo("REPEATABLE READ READ ONLY");
    }

    private static void seedHistory(
        Connection connection,
        long routeVersionId,
        Instant cutoff,
        String route,
        UUID importId
    ) throws Exception {
        insertObservation(connection, routeVersionId, cutoff.minusSeconds(300),
            route + "-source-prediction", route + "-capacity", 1, 10, "S3_BACKFILL", importId);
        insertObservation(connection, routeVersionId, cutoff.minusSeconds(230),
            route + "-source-arrival", route + "-capacity", 2, 8, "S3_BACKFILL", importId);
        insertObservation(connection, routeVersionId, cutoff.minusSeconds(30),
            route + "-cross-prediction", route + "-cross", 1, 12, "S3_BACKFILL", importId);
        insertObservation(connection, routeVersionId, cutoff.plusSeconds(40),
            route + "-cross-arrival", route + "-cross", 2, 9, "LIVE", null);
        insertObservation(connection, routeVersionId, cutoff.plusSeconds(80),
            route + "-capacity-restatement", route + "-capacity", 2, 20, "LIVE", null);
        insertObservation(connection, routeVersionId, cutoff.plusSeconds(120),
            route + "-new-prediction", route + "-new", 2, 11, "LIVE", null);
        insertObservation(connection, routeVersionId, cutoff.plusSeconds(190),
            route + "-new-arrival", route + "-new", 3, 7, "LIVE", null);
    }

    private static List<SeedHourlyRow> sourceRows() {
        ArrayList<SeedHourlyRow> rows = new ArrayList<>();
        rows.add(sourceRow("1650", CUTOFF_1650.minusSeconds(230)));
        rows.add(sourceRow("3330", CUTOFF_3330.minusSeconds(230)));
        return SeedRows.sorted(rows);
    }

    private static SeedHourlyRow sourceRow(
        String route,
        Instant arrival
    ) {
        Instant hour = arrival.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        return new SeedHourlyRow(
            route, 2, hour.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate(), hour,
            new BigDecimal("0.19999999999999996"), new BigDecimal("2.0"),
            new BigDecimal("10.0"), 1);
    }

    private static UUID insertImport(
        Connection connection
    ) throws Exception {
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000811");
        execute(connection, """
            INSERT INTO historical_import_batch (
                id, manifest_sha256, archive_schema_version, archive_kind, inventory_sha256,
                source_cutoff_at, importer_version, target_kind, target_authority_from_min,
                route_validity_policy, status, expected_batch_count, expected_observation_count)
            VALUES (?, ?, 'fixture', 'BASE', ?, '2026-09-02T14:00:00Z', 'fixture', 'LOCAL',
                    '2026-09-02T10:00:00Z', 'EXTEND_EXACT_CURRENT_VERSION', 'COMPLETE', 6, 6)
            """, id, "5".repeat(64), "6".repeat(64));
        return id;
    }

    private static long insertRoute(
        Connection connection,
        String displayName,
        String publicId,
        String digestDigit
    ) throws Exception {
        long routeId = insert(connection, """
            INSERT INTO route (
                public_route_id, source_id, source_route_id, display_name,
                start_stop_name, end_stop_name)
            VALUES (?, 'GBIS', ?, ?, 'start', 'end') RETURNING id
            """, publicId, publicId, displayName);
        long version = insert(connection, """
            INSERT INTO route_version (route_id, turn_sequence, content_digest, valid_from)
            VALUES (?, 2, ?, '2026-08-01T00:00:00Z') RETURNING id
            """, routeId, digestDigit.repeat(64));
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO route_stop (
                route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (?, ?, ?, 'fixture', 'UP', true)
            """)) {
            for (int stop = 1; stop <= 3; stop++) {
                statement.setLong(1, version);
                statement.setInt(2, stop);
                statement.setString(3, publicId + "-" + stop);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return version;
    }

    private static void insertObservation(
        Connection connection,
        long routeVersionId,
        Instant observedAt,
        String attemptKey,
        String vehicleId,
        int passedStopOrder,
        int seats,
        String origin,
        UUID importId
    ) throws Exception {
        String digest = com.gustler.backend.migration.Sha256.of(attemptKey);
        long batch = insert(connection, """
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, completed_at, outcome, provider_rows, stored_rows,
                excluded_rows, normalization_version, collection_strategy_version,
                ingestion_origin, historical_import_batch_id, semantic_batch_digest,
                normalized_record_sha256)
            VALUES (?, ?, 1, ?, ?, ?, ?, 'SUCCESS_ROWS', 1, 1, 0,
                    'normalization-v1.0.0', 'adaptive-kst-v1.0.1', ?, ?, ?, ?) RETURNING id
            """, routeVersionId, offset(observedAt), attemptKey, offset(observedAt), offset(observedAt),
            offset(observedAt), origin, importId, importId == null ? null : digest,
            importId == null ? null : "7".repeat(64));
        insert(connection, """
            INSERT INTO vehicle_observation (
                observation_batch_id, route_version_id, source_row_number, vehicle_id,
                stop_order, stop_id, passed_stop_order, running_state, remaining_seats)
            SELECT ?, ?, 0, ?, ?, stop_id, ?, 2, ?
            FROM route_stop WHERE route_version_id = ? AND stop_order = ? RETURNING id
            """, batch, routeVersionId, vehicleId, passedStopOrder, passedStopOrder,
            seats, routeVersionId, passedStopOrder);
    }

    private static long scalar(
        Connection connection,
        String sql
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static java.time.OffsetDateTime offset(
        Instant instant
    ) {
        return instant.atOffset(java.time.ZoneOffset.UTC);
    }

    private static long insert(
        Connection connection,
        String sql,
        Object... parameters
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void execute(
        Connection connection,
        String sql,
        Object... parameters
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private static void bind(
        PreparedStatement statement,
        Object[] parameters
    ) throws Exception {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }
}
