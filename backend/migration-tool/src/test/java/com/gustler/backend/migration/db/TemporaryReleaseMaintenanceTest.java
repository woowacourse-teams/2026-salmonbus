package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.processor.persistence.jdbc.JdbcForecastWriteBarrier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TemporaryReleaseMaintenanceTest extends PostgresMigrationTestSupport {

    @BeforeAll
    static void migrateHistoricalSchema() {
        new HistoricalSchema().migrate(database);
    }

    @Test
    void freezesWindowBlocksRaceAndDeletesOnlyExactTemporaryDerivatives(
        @TempDir Path directory
    ) throws Exception {
        Seed seed;
        try (Connection connection = connection()) {
            seed = seedTemporaryState(connection);
        }
        ImportSettings settings = settings(directory);
        TemporaryReleaseMaintenance maintenance = new TemporaryReleaseMaintenance();

        maintenance.pause(settings);
        assertForecastWriteBarrier(false);
        maintenance.recoverTemporaryRelease(settings);
        assertForecastWriteBarrier(true);
        assertPrePromotionRecoveryRetainsOpenExclusionWindow();

        TemporaryReleaseMaintenance.PauseResult paused = maintenance.pause(settings);
        TemporaryReleaseMaintenance.FreezeResult frozen =
            maintenance.freeze(settings, true, null);
        assertThat(frozen.finalCutoverAt()).isEqualTo(paused.pausedAt());
        assertThat(frozen.observationBatchHighWater()).isEqualTo(paused.observationBatchHighWater());
        assertThat(frozen.generationCount()).isEqualTo(2);
        assertThat(frozen.rowCount()).isEqualTo(2);
        assertForecastWriteBarrier(false);

        TemporaryReleaseMaintenance.CleanupResult dryRun =
            maintenance.cleanup(settings, false, 1, directory.resolve("cleanup-dry-run.json"), null);
        assertThat(dryRun.executed()).isFalse();
        assertThat(dryRun.targetForecastRows()).isEqualTo(1);
        assertThat(dryRun.targetStatisticsRows()).isEqualTo(2);
        Path mismatched = directory.resolve("cleanup-mismatched.json");
        SecureFiles.writeNew(mismatched, CanonicalJson.bytesOf(Map.of("target", "different")));
        assertThatThrownBy(() -> maintenance.cleanup(settings, true, 1, null, mismatched))
            .isInstanceOf(MigrationException.class)
            .hasMessage("TEMP_CLEANUP_DRY_RUN_RECEIPT_MISMATCH");

        TemporaryReleaseMaintenance.CleanupResult cleaned =
            maintenance.cleanup(
                settings, true, 1, null, directory.resolve("cleanup-dry-run.json"));
        assertThat(cleaned.deletedForecastRows()).isEqualTo(1);
        assertThat(cleaned.deletedStatisticsRows()).isEqualTo(2);
        assertThat(cleaned.observationBefore()).isEqualTo(cleaned.observationAfter());
        assertTemporaryDeploymentAndObservationsRemain(seed);

        TemporaryReleaseMaintenance.CleanupResult rerun =
            maintenance.cleanup(settings, false, 1, null, null);
        assertThat(rerun.targetForecastRows()).isZero();
        assertThat(rerun.targetStatisticsRows()).isZero();

        maintenance.recoverTemporaryRelease(settings);
        assertForecastWriteBarrier(true);
        assertPrePromotionRecoveryRetainsOpenExclusionWindow();
    }

    private static Seed seedTemporaryState(Connection connection) throws Exception {
        long routeId = insert(connection, """
            INSERT INTO route (
                public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
            VALUES ('900000001', 'GBIS', '900000001', 'fixture', 'start', 'end') RETURNING id
            """);
        long versionId = insert(connection, """
            INSERT INTO route_version (route_id, turn_sequence, content_digest, valid_from)
            VALUES (%d, 1, '%s', '2026-09-01T00:00:00Z') RETURNING id
            """.formatted(routeId, "9".repeat(64)));
        execute(connection, """
            INSERT INTO route_stop (
                route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (%d, 1, '900000001', 'fixture-stop', 'UP', true)
            """.formatted(versionId));
        long batchId = insert(connection, """
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, forecast_completed_at, outcome, provider_rows, stored_rows,
                excluded_rows, normalization_version, collection_strategy_version)
            VALUES (%d, '2026-09-02T12:00:00Z', 1, 'temp-fixture', '2026-09-02T12:00:00Z',
                    '2026-09-02T12:00:01Z', '2026-09-02T12:00:02Z', 'SUCCESS_ROWS', 1, 1, 0,
                    'normalization-v1.0.0', 'adaptive-kst-v1.0.1') RETURNING id
            """.formatted(versionId));
        long observationId = insert(connection, """
            INSERT INTO vehicle_observation (
                observation_batch_id, route_version_id, source_row_number, vehicle_id,
                stop_order, stop_id, passed_stop_order, running_state, remaining_seats)
            VALUES (%d, %d, 0, 'fixture-temp-not-source', 1, '900000001', 1, 2, 10)
            RETURNING id
            """.formatted(batchId, versionId));
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
            VALUES (%d, 1, %d, 1, 1, 0, 0.1, 0.1, 10.0,
                    '2026-09-02T12:00:02Z', 'PENDING')
            """.formatted(observationId, versionId));
        insertStatistics(connection, versionId, TemporaryReleaseMaintenance.CARRIER_CALCULATION_VERSION,
            1, TemporaryReleaseMaintenance.TEMPORARY_ACTIVATED_AT.minusSeconds(3600),
            TemporaryReleaseMaintenance.TEMPORARY_ACTIVATED_AT.plusSeconds(3600));
        insertStatistics(connection, versionId, TemporaryReleaseMaintenance.TEMPORARY_CALCULATION_VERSION,
            2, TemporaryReleaseMaintenance.TEMPORARY_ACTIVATED_AT.plusSeconds(7200),
            TemporaryReleaseMaintenance.TEMPORARY_ACTIVATED_AT.plusSeconds(7200));
        return new Seed(batchId, observationId, versionId);
    }

    private static void insertStatistics(
        Connection connection,
        long versionId,
        String calculationVersion,
        int revision,
        Instant dataUntil,
        Instant computedAt
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO stop_demand_statistics (
                route_version_id, stop_order, time_slot, calculation_version, revision,
                average_fill_rate, average_net_boarding_rate, sample_count, day_count,
                data_until, computed_at)
            VALUES (?, 1, 'other', ?, ?, 0.5, 0.0, 1, 1, ?, ?)
            """)) {
            statement.setLong(1, versionId);
            statement.setString(2, calculationVersion);
            statement.setInt(3, revision);
            statement.setObject(4, dataUntil.atOffset(java.time.ZoneOffset.UTC));
            statement.setObject(5, computedAt.atOffset(java.time.ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static void assertTemporaryDeploymentAndObservationsRemain(Seed seed) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT
                (SELECT count(*) FROM model_deployment WHERE id=1 AND state='ACTIVE') AS deployment,
                (SELECT count(*) FROM observation_batch WHERE id=?) AS batch,
                (SELECT count(*) FROM vehicle_observation WHERE id=?) AS observation,
                (SELECT count(*) FROM observation_batch WHERE id=? AND forecast_completed_at IS NOT NULL) AS completed
            """)) {
            statement.setLong(1, seed.batchId());
            statement.setLong(2, seed.observationId());
            statement.setLong(3, seed.batchId());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getLong("deployment")).isOne();
                assertThat(rows.getLong("batch")).isOne();
                assertThat(rows.getLong("observation")).isOne();
                assertThat(rows.getLong("completed")).isOne();
            }
        }
    }

    private static void assertPrePromotionRecoveryRetainsOpenExclusionWindow() throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT
                (SELECT count(*) FROM temporary_statistics_generation_freeze
                 WHERE status IN ('FROZEN', 'CLEANED')) AS active_freezes,
                (SELECT count(*) FROM training_model_release_exclusion
                 WHERE final_cutover_at IS NULL) AS open_window,
                (SELECT writes_paused FROM forecast_cutover_control
                 WHERE singleton = true) AS writes_paused
            """); ResultSet rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getLong("active_freezes")).isZero();
            assertThat(rows.getLong("open_window")).isOne();
            assertThat(rows.getBoolean("writes_paused")).isFalse();
        }
    }

    private static void assertForecastWriteBarrier(boolean expected) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        JdbcForecastWriteBarrier barrier = new JdbcForecastWriteBarrier(JdbcClient.create(dataSource));
        Boolean actual = new TransactionTemplate(new DataSourceTransactionManager(dataSource))
            .execute(status -> barrier.enter());
        assertThat(actual).isEqualTo(expected);
    }

    private static ImportSettings settings(Path directory) {
        Instant seam = Instant.parse("2026-09-02T10:00:00Z");
        return new ImportSettings(
            database, directory, seam, Map.of("3330", seam, "1650", seam.plusSeconds(1)),
            ImportSettings.RouteValidityPolicy.EXTEND_EXACT_CURRENT_VERSION,
            10, 10, 10_000, 0, 2_000, 30, 0, 0, null, null);
    }

    private static long insert(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private record Seed(long batchId, long observationId, long routeVersionId) {
    }
}
