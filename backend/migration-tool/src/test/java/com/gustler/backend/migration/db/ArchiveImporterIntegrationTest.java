package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.archive.ArchiveVerifier;
import com.gustler.backend.processor.SeatSlope;
import com.gustler.backend.processor.VehicleTrajectory;
import com.gustler.backend.processor.persistence.jdbc.JdbcVehicleTrajectoryRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class ArchiveImporterIntegrationTest extends PostgresMigrationTestSupport {

    @BeforeAll
    static void migrateHistoricalSchema() {
        new HistoricalSchema().migrate(database);
    }

    @Test
    void failureResumeIdempotencyConcurrentLiveAndRollbackRemainSafe(
        @TempDir Path directory
    ) throws Exception {
        ArchiveTestFixture.Fixture fixture;
        try (Connection connection = connection()) {
            fixture = ArchiveTestFixture.create(connection, directory.resolve("archive"));
        }
        ImportSettings settings = settings(directory.resolve("archive"), fixture.routeSeed1650());
        ArchiveVerifier.Verification archive = new ArchiveVerifier().verify(settings.archiveDirectory());
        assertThat(new DatabasePreflight().check(settings, archive, false).readOnly()).isTrue();
        new DatabasePreflight().check(settings, archive, true);

        assertThatThrownBy(() -> new ArchiveStager(commits -> {
            if (commits == 1) {
                throw new MigrationException("INJECTED_STAGE_FAILURE");
            }
        }).stage(settings, archive))
            .isInstanceOf(MigrationException.class)
            .extracting(error -> ((MigrationException) error).code())
            .isEqualTo("INJECTED_STAGE_FAILURE");

        ArchiveStager.Result staged = new ArchiveStager().stage(settings, archive);
        assertThat(staged.stagedBatches()).isEqualTo(6);
        assertThat(staged.stagedObservations()).isEqualTo(6);
        assertThat(new ArchiveStager().stage(settings, archive).committedChunks()).isZero();

        ArchiveImportValidator.Result validated = new ArchiveImportValidator().validate(settings, archive);
        assertThat(validated.readyBatches()).isEqualTo(4);
        assertThat(validated.overlapBatches()).isEqualTo(2);
        assertRouteWasExtendedWithoutCreatingAnotherVersion(fixture);
        new ArchiveImportValidator().validate(settings, archive);

        assertThatThrownBy(() -> new ArchiveMerger(transactions -> {
            if (transactions == 1) {
                insertConcurrentLiveBatch(fixture.version3330(), "live-concurrent-fixture");
                throw new MigrationException("INJECTED_MERGE_FAILURE");
            }
        }).merge(settings, archive))
            .isInstanceOf(MigrationException.class)
            .extracting(error -> ((MigrationException) error).code())
            .isEqualTo("INJECTED_MERGE_FAILURE");

        ArchiveMerger.Result merged = new ArchiveMerger().merge(settings, archive);
        assertThat(merged.insertedBatches()).isEqualTo(3);
        assertThat(merged.insertedObservations()).isEqualTo(3);
        assertThat(new ArchiveMerger().merge(settings, archive).insertedBatches()).isZero();

        ImportReconciler.Result reconciled = new ImportReconciler().reconcile(settings, archive);
        assertThat(reconciled.counts().mergedBatches()).isEqualTo(4);
        assertThat(reconciled.counts().mergedObservations()).isEqualTo(4);
        assertThat(reconciled.counts().overlapBatches()).isEqualTo(2);
        assertThat(reconciled.counts().overlapObservations()).isEqualTo(2);
        assertThat(reconciled.continuity()).containsKeys("1650", "3330");
        assertThat(reconciled.continuity().get("3330").gapSeconds()).isEqualTo(7.07582);
        assertThat(reconciled.continuity().get("3330").sharedVehicleCount()).isPositive();
        assertImportedRowsHavePrivateIdentityButNoPlateOrForecast();
        assertOnlineQueueSkipsBackfillOutsideTheStalenessWindow(fixture);
        assertCurrentProcessorCrossesTheBoundary(fixture.live3330Batch());
        assertNoIdentifierLeaksThroughAggregateReceipt(reconciled);

        Path terminalDirectory = directory.resolve("terminal-archive");
        ArchiveTestFixture.createTerminalDelta(
            terminalDirectory, fixture, archive.manifestSha256());
        ImportSettings terminalSettings = settings(terminalDirectory, fixture.routeSeed1650());
        ArchiveVerifier.Verification terminalArchive =
            new ArchiveVerifier().verify(terminalSettings.archiveDirectory());
        new DatabasePreflight().check(terminalSettings, terminalArchive, true);
        new ArchiveStager().stage(terminalSettings, terminalArchive);
        assertThat(new ArchiveImportValidator().validate(terminalSettings, terminalArchive).readyBatches())
            .isOne();
        assertThat(new ArchiveMerger().merge(terminalSettings, terminalArchive).insertedBatches())
            .isOne();
        ImportReconciler.Result terminalReconciled =
            new ImportReconciler().reconcile(terminalSettings, terminalArchive);
        assertTerminalDatasetSeal(terminalArchive, terminalReconciled);
        ImportRollback.Result terminalRollback = new ImportRollback(() ->
            insertConcurrentLiveBatch(fixture.version3330(), "live-during-rollback"))
            .run(terminalSettings, terminalArchive.manifestSha256(), true);
        assertThat(terminalRollback.executed()).isTrue();
        assertThat(terminalRollback.before().liveBatches())
            .isEqualTo(terminalRollback.after().liveBatches());
        assertLiveBatchPreserved("live-during-rollback");
        assertDatasetSealRemoved();

        ImportRollback.Result dryRun = new ImportRollback().run(
            settings, archive.manifestSha256(), false);
        assertThat(dryRun.executed()).isFalse();
        assertThat(dryRun.targetBatches()).isEqualTo(4);

        ImportRollback.Result rolledBack = new ImportRollback().run(
            settings, archive.manifestSha256(), true);
        assertThat(rolledBack.executed()).isTrue();
        assertThat(rolledBack.before().liveBatches()).isEqualTo(rolledBack.after().liveBatches());
        assertThat(new ImportRollback().run(settings, archive.manifestSha256(), true).targetBatches()).isZero();
        assertRouteValidityRestored(fixture);
        assertDatasetSealRemoved();
    }

    private static ImportSettings settings(Path archive, Path routeSeed) {
        Map<String, Instant> authority = Map.of(
            "3330", ArchiveTestFixture.ORIGINAL_3330,
            "1650", ArchiveTestFixture.ORIGINAL_1650);
        return new ImportSettings(
            database,
            archive,
            ArchiveTestFixture.ORIGINAL_3330,
            authority,
            ImportSettings.RouteValidityPolicy.EXTEND_EXACT_CURRENT_VERSION,
            1,
            2,
            10_000,
            0,
            2_000,
            30,
            0,
            0,
            routeSeed,
            null);
    }

    private static void assertRouteWasExtendedWithoutCreatingAnotherVersion(
        ArchiveTestFixture.Fixture fixture
    ) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, valid_from, (SELECT count(*) FROM route_version) AS versions
            FROM route_version ORDER BY id
            """)) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getLong("id")).isEqualTo(fixture.version3330());
                assertThat(rows.getObject("valid_from", java.time.OffsetDateTime.class).toInstant())
                    .isEqualTo(Instant.parse("2026-08-14T08:00:00Z"));
                assertThat(rows.getLong("versions")).isEqualTo(2);
                rows.next();
                assertThat(rows.getLong("id")).isEqualTo(fixture.version1650());
                assertThat(rows.getObject("valid_from", java.time.OffsetDateTime.class).toInstant())
                    .isEqualTo(Instant.parse("2026-08-14T08:01:00Z"));
            }
        }
    }

    private static void insertConcurrentLiveBatch(
        long versionId,
        String attemptKey
    ) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, outcome, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version)
            VALUES (?, now(), 1, ?, now(), now(), 'SUCCESS_EMPTY', 0, 0, 0,
                    'normalization-v1.0.0', 'adaptive-kst-v1.0.1')
            """)) {
            statement.setLong(1, versionId);
            statement.setString(2, attemptKey);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertLiveBatchPreserved(
        String attemptKey
    ) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*) FROM observation_batch
            WHERE attempt_key=? AND ingestion_origin='LIVE'
            """)) {
            statement.setString(1, attemptKey);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getLong(1)).isOne();
            }
        }
    }

    private static void assertImportedRowsHavePrivateIdentityButNoPlateOrForecast() throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT
              count(*) FILTER (WHERE observation.vehicle_id IS NOT NULL) AS identities,
              count(*) FILTER (WHERE observation.plate_number IS NOT NULL) AS plates,
              count(*) FILTER (WHERE batch.forecast_completed_at IS NOT NULL) AS completed,
              count(*) FILTER (WHERE batch.ingestion_origin <> 'S3_BACKFILL') AS wrong_origin
            FROM vehicle_observation observation
            JOIN observation_batch batch ON batch.id = observation.observation_batch_id
            WHERE batch.historical_import_batch_id IS NOT NULL
            """)) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getLong("identities")).isEqualTo(4);
                assertThat(rows.getLong("plates")).isZero();
                assertThat(rows.getLong("completed")).isZero();
                assertThat(rows.getLong("wrong_origin")).isZero();
            }
        }
    }

    private static void assertOnlineQueueSkipsBackfillOutsideTheStalenessWindow(
        ArchiveTestFixture.Fixture fixture
    ) {
        JdbcVehicleTrajectoryRepository repository = trajectoryRepository();
        assertThat(repository.findBatchesAwaitingForecast(
            fixture.version3330(), ArchiveTestFixture.FIRST_LIVE_3330, 100))
            .isNotEmpty()
            .allMatch(batch -> !batch.responseReceivedAt().isBefore(ArchiveTestFixture.FIRST_LIVE_3330));
        assertThat(repository.findBatchesAwaitingForecast(
            fixture.version1650(), ArchiveTestFixture.FIRST_LIVE_1650, 100))
            .isNotEmpty()
            .allMatch(batch -> !batch.responseReceivedAt().isBefore(ArchiveTestFixture.FIRST_LIVE_1650));
        assertThat(repository.findBatchesAwaitingForecast(
            fixture.version3330(), Instant.EPOCH, 100))
            .anyMatch(batch -> batch.responseReceivedAt().isBefore(ArchiveTestFixture.FIRST_LIVE_3330));
    }

    private static void assertCurrentProcessorCrossesTheBoundary(long liveBatchId) {
        JdbcVehicleTrajectoryRepository repository = trajectoryRepository();
        List<VehicleTrajectory> trajectories = repository.readTrajectories(liveBatchId);
        assertThat(trajectories).hasSize(1);
        assertThat(trajectories.getFirst().maximumSeatsEverObserved()).isEqualTo(20);
        assertThat(trajectories.getFirst().seatSlope()).isEqualTo(new SeatSlope.Known(-5));
    }

    private static JdbcVehicleTrajectoryRepository trajectoryRepository() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new JdbcVehicleTrajectoryRepository(JdbcClient.create(dataSource));
    }

    private static void assertNoIdentifierLeaksThroughAggregateReceipt(
        ImportReconciler.Result reconciled
    ) {
        String output = CanonicalJson.stringOf(reconciled.receipt());
        assertThat(output)
            .doesNotContain(ArchiveTestFixture.SYNTHETIC_VEHICLE)
            .doesNotContain("plate_number")
            .doesNotContain("DB_PASSWORD")
            .doesNotContain("raw_response");
    }

    private static void assertRouteValidityRestored(ArchiveTestFixture.Fixture fixture) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, valid_from FROM route_version ORDER BY id
            """)) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(fixture.version3330());
                assertThat(rows.getObject(2, java.time.OffsetDateTime.class).toInstant())
                    .isEqualTo(ArchiveTestFixture.ORIGINAL_3330);
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(fixture.version1650());
                assertThat(rows.getObject(2, java.time.OffsetDateTime.class).toInstant())
                    .isEqualTo(ArchiveTestFixture.ORIGINAL_1650);
            }
        }
    }

    private static void assertTerminalDatasetSeal(
        ArchiveVerifier.Verification archive,
        ImportReconciler.Result reconciled
    ) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT terminal_manifest_sha256, terminal_freeze_receipt_sha256,
                   terminal_import_batch_id
            FROM historical_import_dataset_seal
            """); ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("terminal_manifest_sha256")).isEqualTo(archive.manifestSha256());
            assertThat(rows.getString("terminal_freeze_receipt_sha256"))
                .isEqualTo(archive.manifest().terminalFreeze().terminalReceiptSha256());
            assertThat(rows.getObject("terminal_import_batch_id", java.util.UUID.class))
                .isEqualTo(reconciled.importBatchId());
            assertThat(rows.next()).isFalse();
        }
        assertThat(reconciled.receipt().get("terminalFinalInventorySha256"))
            .isEqualTo(archive.manifest().terminalFreeze().finalInventorySha256());
    }

    private static void assertDatasetSealRemoved() throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "SELECT count(*) FROM historical_import_dataset_seal"); ResultSet rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getLong(1)).isZero();
        }
    }
}
