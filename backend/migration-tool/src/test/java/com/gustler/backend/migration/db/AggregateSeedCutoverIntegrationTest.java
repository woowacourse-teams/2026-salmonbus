package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.processor.StopDemandHourlyTotals;
import com.gustler.backend.processor.persistence.jdbc.JdbcStopDemandStatisticsRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

class AggregateSeedCutoverIntegrationTest extends PostgresMigrationTestSupport {

    private static final Instant CUTOFF_1650 = Instant.parse("2026-09-02T12:49:33.041299Z");
    private static final Instant CUTOFF_3330 = Instant.parse("2026-09-02T10:27:52.390820Z");

    @BeforeAll
    static void migrateHistoricalSchema() {
        new HistoricalSchema().migrate(database);
    }

    @Test
    void providerFixtureCompletesDryRunApplyRollbackReapplyAndFinalServingVerification(
        @TempDir Path directory
    ) throws Exception {
        ProviderSeedFixture.Files files = ProviderSeedFixture.writeTo(directory);
        Map<String, Long> routeVersions;
        try (Connection connection = connection()) {
            routeVersions = installDatabaseFixture(connection);
        }
        ImportSettings settings = settings(directory);
        TemporaryReleaseMaintenance maintenance = new TemporaryReleaseMaintenance();
        TemporaryReleaseMaintenance.PauseResult paused = maintenance.pause(settings);
        insertConcurrentLiveAfterBoundary(routeVersions.get("3330"), paused);
        maintenance.freeze(settings, true, null);

        AggregateSeedCutover cutover = new AggregateSeedCutover(
            files.reader(), new ProviderFixtureReplay());
        assertThatThrownBy(() -> cutover.dryRun(
            settings, files.seed(), files.receipt(), directory.resolve("before-cleanup.json")))
            .isInstanceOf(MigrationException.class)
            .hasMessage("TEMP_FREEZE_CUTOVER_BOUNDARY_MISMATCH");

        Path cleanupDryRunReceipt = directory.resolve("cleanup-dry-run.json");
        maintenance.cleanup(settings, false, 10, cleanupDryRunReceipt, null);
        Path cleanupReceipt = directory.resolve("cleanup-executed.json");
        maintenance.cleanup(settings, true, 10, cleanupReceipt, cleanupDryRunReceipt);
        Path cleanupNoOpReceipt = directory.resolve("cleanup-noop.json");
        maintenance.cleanup(settings, false, 10, cleanupNoOpReceipt, null);
        Path plan = directory.resolve("seed-plan.json");
        AggregateSeedCutover.PlanResult dryRun = cutover.dryRun(
            settings, files.seed(), files.receipt(), plan);
        assertThat(dryRun.sourceHourlyRows()).isEqualTo(100);
        assertThat(dryRun.deltaHourlyRows()).isZero();
        assertThat(dryRun.combinedHourlyRows()).isEqualTo(100);
        assertThat(dryRun.generationCount()).isEqualTo(2);

        assertThatThrownBy(() -> maintenance.unpause(settings))
            .isInstanceOf(MigrationException.class)
            .hasMessage("FORMAL_SEED_MISSING");

        AggregateSeedCutover.PlanResult applied = cutover.apply(
            settings, files.seed(), files.receipt(), plan, directory.resolve("apply-receipt.json"));
        assertThat(applied.planSha256()).isEqualTo(dryRun.planSha256());
        assertThat(applied.alreadyApplied()).isFalse();
        assertProviderReceiptMatchesDatabaseReadBack();
        assertSeedVisibleToWorker(routeVersions.get("3330"), paused.pausedAt(), 1_062);

        AggregateSeedCutover.RollbackResult rollbackDryRun = cutover.rollback(
            settings, applied.planSha256(), false, directory.resolve("rollback-dry-run.json"));
        assertThat(rollbackDryRun.targetHourlyRows()).isEqualTo(100);
        assertThat(rollbackDryRun.targetGenerations()).isEqualTo(2);
        cutover.rollback(
            settings, applied.planSha256(), true, directory.resolve("rollback-executed.json"));
        assertNoAppliedSeedRows();

        AggregateSeedCutover.PlanResult reapplied = cutover.apply(
            settings, files.seed(), files.receipt(), plan, directory.resolve("reapply-receipt.json"));
        assertThat(reapplied.planSha256()).isEqualTo(applied.planSha256());
        assertThat(reapplied.alreadyApplied()).isFalse();
        assertThat(cutover.apply(
            settings, files.seed(), files.receipt(), plan, directory.resolve("idempotent-receipt.json"))
            .alreadyApplied()).isTrue();

        long formalDeploymentId = activateFormalDeployment(paused.pausedAt());
        maintenance.unpause(settings);
        insertFormalServingForecasts(routeVersions, formalDeploymentId, paused.pausedAt());
        Path cleanupApproval = privateJson(directory.resolve("cleanup-approval.json"), Map.of("approved", true));
        Path seedApproval = privateJson(directory.resolve("seed-approval.json"), Map.of("approved", true));
        Path provisionalApproval = privateJson(
            directory.resolve("APPROVAL.md"), Map.of("approved", "provisional"));
        Path promotionApproval = privateJson(
            directory.resolve("promotion-approval.json"), Map.of("approved", true));
        Path promotionReceipt = privateJson(directory.resolve("promotion-receipt.json"), Map.of(
            "releaseId", TemporaryReleaseMaintenance.FORMAL_RELEASE_ID,
            "bundleDigest", TemporaryReleaseMaintenance.FORMAL_BUNDLE_DIGEST,
            "promoteOnStartRestoredFalse", true));
        assertThatThrownBy(() -> new SeedCutoverVerifier().verify(
            settings, plan, cleanupReceipt, cleanupNoOpReceipt,
            cleanupApproval, seedApproval, provisionalApproval, promotionApproval, promotionReceipt,
            directory.resolve("wrong-source-final-receipt.json")))
            .isInstanceOf(MigrationException.class)
            .hasMessage("SEED_FINAL_SOURCE_IDENTITY_INVALID");
        Path finalReceipt = directory.resolve("final-receipt.json");
        SeedCutoverVerifier verifier = new SeedCutoverVerifier(
            new SeedCutoverVerifier.SourceIdentity(
                ProviderSeedFixture.COMPRESSED_SHA256,
                ProviderSeedFixture.RECEIPT_SHA256,
                ProviderSeedFixture.CANONICAL_SHA256,
                ProviderSeedFixture.ROWS_SHA256,
                ProviderSeedFixture.PRIMARY_KEY_SHA256),
            com.gustler.backend.migration.Sha256.of(provisionalApproval));
        SeedCutoverVerifier.Result verified = verifier.verify(
            settings, plan, cleanupReceipt, cleanupNoOpReceipt,
            cleanupApproval, seedApproval, provisionalApproval, promotionApproval, promotionReceipt,
            finalReceipt);
        assertThat(verified.newForecastRows()).isEqualTo(2);
        assertFinalReceipt(finalReceipt, paused.pausedAt());
        assertThat(controlPaused()).isFalse();
    }

    private static long activateFormalDeployment(
        Instant finalCutoverAt
    ) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(connection, """
                UPDATE model_deployment
                SET state='RETIRED', retired_at=clock_timestamp()
                WHERE id=1 AND state='ACTIVE'
                """);
            long id = insert(connection, """
                INSERT INTO model_deployment (
                    deployment_key, release_id, model_key, model_version, bundle_digest,
                    prediction_target_version, calculation_version, supported_scope_digest,
                    data_until, state, predecessor_deployment_id, activated_at)
                VALUES ('00000000-0000-4000-8000-000000000002', ?, 'fixture', 'fixture', ?,
                        'fixture', 'observed-max-capacity-v1', ?, ?, 'ACTIVE', 1, clock_timestamp())
                RETURNING id
                """, TemporaryReleaseMaintenance.FORMAL_RELEASE_ID,
                TemporaryReleaseMaintenance.FORMAL_BUNDLE_DIGEST, "7".repeat(64), offset(finalCutoverAt));
            connection.commit();
            return id;
        }
    }

    private static void insertFormalServingForecasts(
        Map<String, Long> routeVersions,
        long formalDeploymentId,
        Instant finalCutoverAt
    ) throws Exception {
        try (Connection connection = connection()) {
            int ordinal = 0;
            for (String route : List.of("1650", "3330")) {
                long routeVersionId = routeVersions.get(route);
                int revision;
                try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT revision FROM stop_demand_seed_generation
                    WHERE route_version_id=?
                    """)) {
                    statement.setLong(1, routeVersionId);
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        revision = rows.getInt(1);
                    }
                }
                Instant observedAt = finalCutoverAt.plusSeconds(++ordinal);
                long observation = insertObservation(
                    connection, routeVersionId, observedAt, "formal-" + route, 1, 20);
                execute(connection, """
                    INSERT INTO seat_forecast (
                        vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
                        model_deployment_id, demand_statistics_revision, seat_full_chance_raw,
                        seat_full_chance, expected_seats, generated_at, scoring_state)
                    VALUES (?, 2, ?, 1, ?, ?, 0.1, 0.1, 10.0, ?, 'PENDING')
                    """, observation, routeVersionId, formalDeploymentId, revision, offset(observedAt));
            }
        }
    }

    private static Path privateJson(
        Path path,
        Map<String, Object> value
    ) {
        SecureFiles.writeNew(path, CanonicalJson.bytesOf(value));
        return path;
    }

    private static void assertFinalReceipt(
        Path path,
        Instant finalCutoverAt
    ) throws Exception {
        tools.jackson.databind.JsonNode root = CanonicalJson.parse(
            java.nio.file.Files.readAllBytes(path), "TEST_FINAL_RECEIPT_INVALID");
        assertThat(root.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()))
            .isEqualTo(Set.of(
                "approval", "cleanup", "databaseWrite", "deployment", "finalCutoverAt",
                "invariants", "mergedSeed", "officialGeneration", "privacy", "rdsReplay",
                "rollback", "schemaVersion", "serving", "sourceSeed", "status"));
        assertThat(root.path("schemaVersion").stringValue())
            .isEqualTo("v4-1-seed-cutover-receipt-v1");
        assertThat(root.path("status").stringValue()).isEqualTo("PASS");
        assertThat(root.path("finalCutoverAt").stringValue()).isEqualTo(finalCutoverAt.toString());
        assertThat(root.path("officialGeneration").path("routeCoverageComplete").booleanValue()).isTrue();
        assertThat(root.path("officialGeneration").path("frozenGenerationIntersectionCount").intValue())
            .isZero();
        assertThat(root.path("officialGeneration").path("sourceSeedReceiptSha256").stringValue())
            .isEqualTo(ProviderSeedFixture.RECEIPT_SHA256);
        for (String route : List.of("1650", "3330")) {
            tools.jackson.databind.JsonNode generation =
                root.path("officialGeneration").path("routes").path(route);
            assertThat(generation.path("cellCount").intValue()).isPositive();
            assertThat(generation.path("aggregateCellsSha256").stringValue())
                .matches("[0-9a-f]{64}");
        }
        assertThat(root.path("serving").path("newForecastRowCount").longValue()).isEqualTo(2);
        assertThat(root.path("serving").path("unresolvedGenerationReferenceCount").longValue()).isZero();
    }

    private static Map<String, Long> installDatabaseFixture(
        Connection connection
    ) throws Exception {
        Map<String, Long> versions = Map.of(
            "1650", insertRoute(connection, "1650", "900000010", "1", 89),
            "3330", insertRoute(connection, "3330", "900000020", "2", 85));
        long observation = insertObservation(
            connection, versions.get("3330"), CUTOFF_3330.plusSeconds(1), "temp-source", 1, 10);
        long deploymentId = insert(connection, """
            INSERT INTO model_deployment (
                deployment_key, release_id, model_key, model_version, bundle_digest,
                prediction_target_version, calculation_version, supported_scope_digest,
                data_until, state, activated_at)
            VALUES ('00000000-0000-4000-8000-000000000001',
                    'salmonbus-d57370be9195520e', 'fixture', 'fixture',
                    'd57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a',
                    'fixture', 'seat-feature-contract-v4-1-2026-09-02', '%s',
                    '2026-09-02T11:50:00Z', 'ACTIVE', '2026-09-02T11:55:04.729493Z')
            RETURNING id
            """.formatted("8".repeat(64)));
        assertThat(deploymentId).isEqualTo(1);
        execute(connection, """
            INSERT INTO seat_forecast (
                vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
                model_deployment_id, demand_statistics_revision, seat_full_chance_raw,
                seat_full_chance, expected_seats, generated_at, scoring_state)
            VALUES (?, 2, ?, 1, 1, 0, 0.1, 0.1, 10.0, '2026-09-02T13:00:00Z', 'PENDING')
            """, observation, versions.get("3330"));
        for (long version : versions.values()) {
            execute(connection, """
                INSERT INTO stop_demand_statistics (
                    route_version_id, stop_order, time_slot, calculation_version, revision,
                    average_fill_rate, average_net_boarding_rate, sample_count, day_count,
                    data_until, computed_at)
                VALUES (?, 2, 'other', 'observed-max-capacity-v1', 1,
                        0.5, 0.0, 1, 1, '2026-09-02T13:00:00Z', '2026-09-02T13:00:00Z')
                """, version);
        }
        return versions;
    }

    private static long insertRoute(
        Connection connection,
        String displayName,
        String publicId,
        String digestDigit,
        int stops
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
            for (int stop = 1; stop <= stops; stop++) {
                statement.setLong(1, version);
                statement.setInt(2, stop);
                statement.setString(3, publicId + "-" + stop);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return version;
    }

    private static long insertObservation(
        Connection connection,
        long routeVersionId,
        Instant observedAt,
        String attemptKey,
        int passedStopOrder,
        int seats
    ) throws Exception {
        long batch = insert(connection, """
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, forecast_completed_at, completed_at, outcome,
                provider_rows, stored_rows, excluded_rows, normalization_version,
                collection_strategy_version, ingestion_origin)
            VALUES (?, ?, 1, ?, ?, ?, ?, ?, 'SUCCESS_ROWS', 1, 1, 0,
                    'normalization-v1.0.0', 'adaptive-kst-v1.0.1', 'LIVE') RETURNING id
            """, routeVersionId, offset(observedAt), attemptKey, offset(observedAt), offset(observedAt),
            offset(observedAt), offset(observedAt));
        return insert(connection, """
            INSERT INTO vehicle_observation (
                observation_batch_id, route_version_id, source_row_number, vehicle_id,
                stop_order, stop_id, passed_stop_order, running_state, remaining_seats)
            SELECT ?, ?, 0, 'fixture-private', ?, stop_id, ?, 2, ?
            FROM route_stop WHERE route_version_id = ? AND stop_order = ? RETURNING id
            """, batch, routeVersionId, passedStopOrder, passedStopOrder, seats,
            routeVersionId, passedStopOrder);
    }

    private static void insertConcurrentLiveAfterBoundary(
        long routeVersionId,
        TemporaryReleaseMaintenance.PauseResult paused
    ) throws Exception {
        try (Connection connection = connection()) {
            insertObservation(
                connection, routeVersionId, paused.pausedAt().minusNanos(1_000),
                "after-high-water", 1, 20);
        }
    }

    private static void assertSeedVisibleToWorker(
        long routeVersionId,
        Instant dataUntil,
        int expectedSamples
    ) {
        JdbcStopDemandStatisticsRepository repository = new JdbcStopDemandStatisticsRepository(
            JdbcClient.create(dataSource()));
        List<StopDemandHourlyTotals> totals = repository.readHourlyTotals(
            routeVersionId, dataUntil.plusSeconds(1));
        assertThat(totals.stream().mapToInt(StopDemandHourlyTotals::sampleCount).sum())
            .isEqualTo(expectedSamples);
    }

    private static void assertProviderReceiptMatchesDatabaseReadBack() throws Exception {
        ArrayList<SeedHourlyRow> values = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT route.display_name, hourly.stop_order, hourly.arrival_date_kst,
                   hourly.arrival_hour_start, hourly.fill_rate_total,
                   hourly.net_boarding_total, hourly.capacity_total, hourly.sample_count
            FROM stop_demand_seed_hourly_total hourly
            JOIN route_version version ON version.id=hourly.route_version_id
            JOIN route ON route.id=version.route_id
            JOIN stop_demand_seed_import seed ON seed.id=hourly.seed_import_id
            WHERE seed.status='APPLIED'
            ORDER BY route.display_name, hourly.arrival_hour_start, hourly.stop_order
            """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(new SeedHourlyRow(
                    rows.getString("display_name"), rows.getInt("stop_order"),
                    rows.getObject("arrival_date_kst", LocalDate.class),
                    rows.getObject("arrival_hour_start", OffsetDateTime.class).toInstant(),
                    rows.getBigDecimal("fill_rate_total"), rows.getBigDecimal("net_boarding_total"),
                    rows.getBigDecimal("capacity_total"), rows.getInt("sample_count")));
            }
        }
        SeedRows.AggregateSet actual = SeedRows.aggregateSet(values);
        SeedRows.AggregateSet expected = ProviderSeedFixture.expectedAggregates();
        assertThat(actual.global().numericallyEquals(expected.global())).isTrue();
        assertThat(actual.routes().get("1650").numericallyEquals(expected.routes().get("1650"))).isTrue();
        assertThat(actual.routes().get("3330").numericallyEquals(expected.routes().get("3330"))).isTrue();
        assertThat(SeedRows.canonicalRowsSha256(values)).isEqualTo(ProviderSeedFixture.ROWS_SHA256);
        assertThat(SeedRows.primaryKeySha256(values)).isEqualTo(ProviderSeedFixture.PRIMARY_KEY_SHA256);
    }

    private static void assertNoAppliedSeedRows() throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT
              (SELECT count(*) FROM stop_demand_seed_import WHERE status='APPLIED') AS imports,
              (SELECT count(*) FROM stop_demand_seed_hourly_total) AS hourly,
              (SELECT count(*) FROM stop_demand_seed_generation) AS generations
            """); ResultSet rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getLong("imports")).isZero();
            assertThat(rows.getLong("hourly")).isZero();
            assertThat(rows.getLong("generations")).isZero();
        }
    }

    private static boolean controlPaused() throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "SELECT writes_paused FROM forecast_cutover_control WHERE singleton=true");
            ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static ImportSettings settings(
        Path directory
    ) {
        return new ImportSettings(
            database, directory, CUTOFF_3330, Map.of("3330", CUTOFF_3330, "1650", CUTOFF_1650),
            ImportSettings.RouteValidityPolicy.EXTEND_EXACT_CURRENT_VERSION,
            10, 10, 10_000, 0, 2_000, 30, 0, 0, null, null);
    }

    private static org.postgresql.ds.PGSimpleDataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    private static java.time.OffsetDateTime offset(
        Instant value
    ) {
        return value.atOffset(java.time.ZoneOffset.UTC);
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

    private static final class ProviderFixtureReplay extends RdsObservationDeltaBuilder {

        @Override
        Delta build(
            Connection connection,
            AggregateSeedPayload source,
            TemporaryReleaseMaintenance.CutoverBoundary boundary
        ) throws java.sql.SQLException {
            if (!connection.isReadOnly()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
                throw new MigrationException("TEST_REPLAY_NOT_REPEATABLE_READ_ONLY");
            }
            Map<String, RouteReplayCounts> routes = Map.of(
                "1650", new RouteReplayCounts(0, 0, 0, 0, 0, 50, 1_378),
                "3330", new RouteReplayCounts(0, 0, 0, 0, 0, 50, 1_062));
            SeedRows.AggregateSet zero = SeedRows.aggregateSet(List.of());
            return new Delta(
                source.rows(), List.of(), routes, source.aggregates(), zero,
                source.canonicalRowsSha256(), source.canonicalRowsSha256(),
                source.primaryKeySha256(), 0, 0, 0,
                "a".repeat(64), "b".repeat(64), Map.of("fixture", true));
        }
    }
}
