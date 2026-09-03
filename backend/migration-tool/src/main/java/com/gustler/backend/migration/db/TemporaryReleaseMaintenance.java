package com.gustler.backend.migration.db;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.Sha256;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TemporaryReleaseMaintenance {

    public static final long TEMPORARY_DEPLOYMENT_ID = 1;
    public static final String TEMPORARY_RELEASE_ID = "salmonbus-d57370be9195520e";
    public static final String TEMPORARY_BUNDLE_DIGEST =
        "d57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a";
    public static final String TEMPORARY_CALCULATION_VERSION = "seat-feature-contract-v4-1-2026-09-02";
    public static final String CARRIER_CALCULATION_VERSION = "observed-max-capacity-v1";
    public static final Instant TEMPORARY_ACTIVATED_AT =
        Instant.parse("2026-09-02T11:55:04.729493Z");
    public static final String FORMAL_RELEASE_ID = "v41b-8194bde56d86f365afd6";
    public static final String FORMAL_BUNDLE_DIGEST =
        "9bb1a5ac22a317931d409f98cb5e9b1935c1b346ae86e4ba05d084614c863632";

    private static final int LOCK_NAMESPACE = 1_920_224_641;
    private static final int LOCK_KEY = 4_041;

    public PauseResult pause(
        ImportSettings settings
    ) {
        return DatabaseConnections.transaction(settings.database(), connection -> {
            SqlSafety.setLocalTimeouts(connection, settings);
            acquireExclusiveFence(connection);
            requireTemporaryIdentity(connection, "ACTIVE");
            requireOnlyTemporaryDeploymentActive(connection);
            long observationBatchHighWater = captureObservationBoundary(connection);
            UUID cutoverId = UUID.randomUUID();
            Instant pausedAt = pauseWrites(connection, cutoverId, observationBatchHighWater);
            return new PauseResult(cutoverId, pausedAt, observationBatchHighWater);
        });
    }

    public FreezeResult freeze(
        ImportSettings settings,
        boolean execute,
        java.nio.file.Path receiptOutput
    ) {
        FreezeResult result = DatabaseConnections.transaction(settings.database(), connection -> {
            if (!execute) {
                SqlSafety.setTransactionReadOnly(connection);
            }
            SqlSafety.setLocalTimeouts(connection, settings);
            if (execute) {
                acquireExclusiveFence(connection);
            }
            CutoverBoundary boundary = requireControlPaused(connection);
            requireTemporaryIdentity(connection, "ACTIVE");
            requireOnlyTemporaryDeploymentActive(connection);
            List<Generation> generations = generations(connection, boundary.finalCutoverAt());
            Map<String, Object> manifest = generationManifest(boundary, generations);
            String manifestSha256 = Sha256.of(CanonicalJson.bytesOf(manifest));
            UUID freezeId = UUID.nameUUIDFromBytes(
                ("salmonbus-temp-generation-freeze\n" + manifestSha256)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (execute) {
                persistFreeze(connection, freezeId, boundary, manifestSha256, generations);
            }
            return new FreezeResult(
                freezeId, execute, boundary.finalCutoverAt(), boundary.observationBatchHighWater(), manifestSha256,
                generations.size(), generations.stream().mapToLong(Generation::rowCount).sum(), manifest);
        });
        if (receiptOutput != null) {
            SecureFiles.writeNew(receiptOutput, CanonicalJson.bytesOf(result.manifest()));
        }
        return result;
    }

    public CleanupResult cleanup(
        ImportSettings settings,
        boolean execute,
        int deleteBatchRows,
        java.nio.file.Path receiptOutput,
        java.nio.file.Path dryRunReceipt
    ) {
        if (deleteBatchRows < 1 || deleteBatchRows > 10_000) {
            throw new MigrationException("TEMP_CLEANUP_BATCH_LIMIT_INVALID");
        }
        CleanupPlan plan = DatabaseConnections.transaction(settings.database(), connection -> {
            if (!execute) {
                SqlSafety.setTransactionReadOnly(connection);
            }
            SqlSafety.setLocalTimeouts(connection, settings);
            return cleanupPlan(connection);
        });
        if (execute) {
            requireCleanupAuthority(plan, dryRunReceipt);
        } else if (dryRunReceipt != null) {
            throw new MigrationException("TEMP_CLEANUP_DRY_RUN_AUTHORITY_UNEXPECTED");
        }
        long deletedForecasts = 0;
        long deletedStatistics = 0;
        if (execute) {
            long deleted;
            do {
                deleted = deleteForecastChunk(settings, deleteBatchRows);
                deletedForecasts += deleted;
                throttle(settings.throttleMillis());
            } while (deleted != 0);
            do {
                deleted = deleteStatisticsChunk(settings, plan.freezeId(), deleteBatchRows);
                deletedStatistics += deleted;
                throttle(settings.throttleMillis());
            } while (deleted != 0);
        }
        long finalDeletedForecasts = deletedForecasts;
        long finalDeletedStatistics = deletedStatistics;
        CleanupResult result = DatabaseConnections.transaction(settings.database(), connection -> {
            if (!execute) {
                SqlSafety.setTransactionReadOnly(connection);
            }
            SqlSafety.setLocalTimeouts(connection, settings);
            ObservationSnapshot after = observationSnapshot(connection, plan.before().highWaterBatchId());
            if (execute) {
                requireObservationUnchanged(plan.before(), after);
                requireCleanupEmpty(connection, plan.freezeId());
                markFreezeCleaned(connection, plan.freezeId());
            }
            Map<String, Object> receipt = cleanupReceipt(
                execute, plan, after, finalDeletedForecasts, finalDeletedStatistics);
            return new CleanupResult(
                execute, plan.freezeId(), plan.targetForecastRows(), plan.targetStatisticsRows(),
                finalDeletedForecasts, finalDeletedStatistics, plan.before(), after, receipt);
        });
        if (receiptOutput != null) {
            SecureFiles.writeNew(receiptOutput, CanonicalJson.bytesOf(result.receipt()));
        }
        return result;
    }

    private static void requireCleanupAuthority(
        CleanupPlan plan,
        java.nio.file.Path dryRunReceipt
    ) {
        if (dryRunReceipt == null) {
            throw new MigrationException("TEMP_CLEANUP_DRY_RUN_RECEIPT_REQUIRED");
        }
        SecureFiles.requirePrivateRegularFile(dryRunReceipt);
        byte[] actual;
        try {
            actual = java.nio.file.Files.readAllBytes(dryRunReceipt);
        } catch (java.io.IOException e) {
            throw new MigrationException("TEMP_CLEANUP_DRY_RUN_RECEIPT_INVALID", e);
        }
        Map<String, Object> expected = cleanupReceipt(false, plan, plan.before(), 0, 0);
        if (!java.util.Arrays.equals(actual, CanonicalJson.bytesOf(expected))) {
            throw new MigrationException("TEMP_CLEANUP_DRY_RUN_RECEIPT_MISMATCH");
        }
    }

    public void unpause(
        ImportSettings settings
    ) {
        DatabaseConnections.transaction(settings.database(), connection -> {
            SqlSafety.setLocalTimeouts(connection, settings);
            acquireExclusiveFence(connection);
            CutoverBoundary boundary = requireControlPaused(connection);
            UUID freezeId = latestFreezeId(connection);
            requireCleanupEmpty(connection, freezeId);
            requireFreezeMatchesBoundary(connection, freezeId, boundary, "CLEANED");
            AggregateSeedCutover.requireAppliedForBoundary(connection, boundary);
            FormalDeployment formal = requireFormalReplacement(connection, boundary);
            AggregateSeedCutover.recordFormalActivation(
                connection, boundary, formal.id(), formal.activatedAt());
            unpauseWrites(connection);
            return null;
        });
    }

    public void recoverTemporaryRelease(
        ImportSettings settings
    ) {
        DatabaseConnections.transaction(settings.database(), connection -> {
            SqlSafety.setLocalTimeouts(connection, settings);
            acquireExclusiveFence(connection);
            CutoverBoundary boundary = requireControlPaused(connection);
            requireTemporaryIdentity(connection, "ACTIVE");
            requireOnlyTemporaryDeploymentActive(connection);
            AggregateSeedCutover.rollbackForTemporaryRecovery(connection, boundary);
            abortFreeze(connection, boundary);
            unpauseWrites(connection);
            return null;
        });
    }

    private static void unpauseWrites(
        Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE forecast_cutover_control
            SET writes_paused = false, cutover_id = NULL, pause_reason = NULL,
                paused_at = NULL, observation_batch_high_water = NULL, updated_at = now()
            WHERE singleton = true AND writes_paused = true
            """)) {
            if (statement.executeUpdate() != 1) {
                throw new MigrationException("TEMP_CUTOVER_CONTROL_CHANGED");
            }
        }
    }

    private static CleanupPlan cleanupPlan(
        Connection connection
    ) throws SQLException {
        CutoverBoundary boundary = requireControlPaused(connection);
        requireTemporaryIdentity(connection, "ACTIVE");
        requireOnlyTemporaryDeploymentActive(connection);
        UUID freezeId = latestFreezeId(connection);
        requireFreezeMatchesBoundary(connection, freezeId, boundary, null);
        long forecasts = scalar(connection,
            "SELECT count(*) FROM seat_forecast WHERE model_deployment_id = 1");
        long statistics = exactStatisticsCount(connection, freezeId);
        ObservationSnapshot before = observationSnapshot(
            connection, boundary.observationBatchHighWater());
        return new CleanupPlan(freezeId, forecasts, statistics, before);
    }

    private static void acquireExclusiveFence(
        Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT true FROM pg_advisory_xact_lock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, LOCK_KEY);
            statement.executeQuery().close();
        }
    }

    private static long captureObservationBoundary(
        Connection connection
    ) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(
            "LOCK TABLE observation_batch, vehicle_observation IN SHARE MODE")) {
            lock.execute();
        }
        return scalar(connection, "SELECT COALESCE(max(id), 0) FROM observation_batch");
    }

    private static Instant pauseWrites(
        Connection connection,
        UUID cutoverId,
        long observationBatchHighWater
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE forecast_cutover_control
            SET writes_paused = true, cutover_id = ?, pause_reason = 'FORMAL_MODEL_REPLACEMENT',
                paused_at = clock_timestamp(), observation_batch_high_water = ?, updated_at = now()
            WHERE singleton = true AND writes_paused = false
            RETURNING paused_at
            """)) {
            statement.setObject(1, cutoverId);
            statement.setLong(2, observationBatchHighWater);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("TEMP_CUTOVER_ALREADY_PAUSED");
                }
                return rows.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    private static void requireOnlyTemporaryDeploymentActive(
        Connection connection
    ) throws SQLException {
        if (scalar(connection, "SELECT count(*) FROM model_deployment WHERE state = 'ACTIVE'") != 1) {
            throw new MigrationException("TEMP_RECOVERY_ACTIVE_DEPLOYMENT_CONFLICT");
        }
    }

    static void requireTemporaryActiveOnly(
        Connection connection
    ) throws SQLException {
        requireTemporaryIdentity(connection, "ACTIVE");
        requireOnlyTemporaryDeploymentActive(connection);
    }

    static UUID requireFreezeForBoundary(
        Connection connection,
        CutoverBoundary boundary,
        String status
    ) throws SQLException {
        UUID freezeId = latestFreezeId(connection);
        requireFreezeMatchesBoundary(connection, freezeId, boundary, status);
        return freezeId;
    }

    static CutoverBoundary requireControlPaused(
        Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT writes_paused, paused_at, observation_batch_high_water
            FROM forecast_cutover_control WHERE singleton = true
            """)) {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1)) {
                    throw new MigrationException("TEMP_CUTOVER_NOT_PAUSED");
                }
                OffsetDateTime pausedAt = rows.getObject("paused_at", OffsetDateTime.class);
                Long highWater = rows.getObject("observation_batch_high_water", Long.class);
                if (pausedAt == null || highWater == null || highWater < 0) {
                    throw new MigrationException("TEMP_CUTOVER_BOUNDARY_INVALID");
                }
                return new CutoverBoundary(pausedAt.toInstant(), highWater);
            }
        }
    }

    private static void abortFreeze(
        Connection connection,
        CutoverBoundary boundary
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE temporary_statistics_generation_freeze
            SET status = 'ABORTED'
            WHERE release_id = ? AND bundle_digest = ? AND cutover_at = ?
              AND observation_batch_high_water = ? AND status IN ('FROZEN', 'CLEANED')
            """)) {
            statement.setString(1, TEMPORARY_RELEASE_ID);
            statement.setString(2, TEMPORARY_BUNDLE_DIGEST);
            statement.setObject(3, offset(boundary.finalCutoverAt()));
            statement.setLong(4, boundary.observationBatchHighWater());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE training_model_release_exclusion SET final_cutover_at = NULL
            WHERE release_id = ? AND bundle_digest = ? AND final_cutover_at = ?
            """)) {
            statement.setString(1, TEMPORARY_RELEASE_ID);
            statement.setString(2, TEMPORARY_BUNDLE_DIGEST);
            statement.setObject(3, offset(boundary.finalCutoverAt()));
            statement.executeUpdate();
        }
    }

    private static void requireTemporaryIdentity(
        Connection connection,
        String state
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*) FROM model_deployment
            WHERE id = ? AND release_id = ? AND bundle_digest = ?
              AND calculation_version = ? AND activated_at = ? AND state = ?
            """)) {
            statement.setLong(1, TEMPORARY_DEPLOYMENT_ID);
            statement.setString(2, TEMPORARY_RELEASE_ID);
            statement.setString(3, TEMPORARY_BUNDLE_DIGEST);
            statement.setString(4, TEMPORARY_CALCULATION_VERSION);
            statement.setObject(5, offset(TEMPORARY_ACTIVATED_AT));
            statement.setString(6, state);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != 1) {
                    throw new MigrationException("TEMP_DEPLOYMENT_EXACT_IDENTITY_MISMATCH");
                }
            }
        }
    }

    private static List<Generation> generations(
        Connection connection,
        Instant cutoverAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT route_version_id, calculation_version, revision, data_until, computed_at, count(*) AS row_count
            FROM stop_demand_statistics
            WHERE calculation_version IN (?, ?)
              AND computed_at >= ? AND computed_at < ?
            GROUP BY route_version_id, calculation_version, revision, data_until, computed_at
            ORDER BY route_version_id, calculation_version, revision, data_until, computed_at
            """)) {
            statement.setString(1, TEMPORARY_CALCULATION_VERSION);
            statement.setString(2, CARRIER_CALCULATION_VERSION);
            statement.setObject(3, offset(TEMPORARY_ACTIVATED_AT));
            statement.setObject(4, offset(cutoverAt));
            List<Generation> generations = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    generations.add(new Generation(
                        rows.getLong("route_version_id"), rows.getString("calculation_version"),
                        rows.getInt("revision"), rows.getObject("data_until", OffsetDateTime.class).toInstant(),
                        rows.getObject("computed_at", OffsetDateTime.class).toInstant(),
                        rows.getInt("row_count")));
                }
            }
            return List.copyOf(generations);
        }
    }

    private static Map<String, Object> generationManifest(
        CutoverBoundary boundary,
        List<Generation> generations
    ) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", "salmonbus-temp-generation-exclusion-v1");
        root.put("temporary_deployment", Map.of(
            "id", TEMPORARY_DEPLOYMENT_ID,
            "release_id", TEMPORARY_RELEASE_ID,
            "bundle_digest", TEMPORARY_BUNDLE_DIGEST,
            "calculation_version", TEMPORARY_CALCULATION_VERSION,
            "activated_at", TEMPORARY_ACTIVATED_AT.toString()));
        root.put("window", Map.of(
            "from_inclusive", TEMPORARY_ACTIVATED_AT.toString(),
            "through_exclusive", boundary.finalCutoverAt().toString(),
            "observation_batch_high_water", boundary.observationBatchHighWater()));
        root.put("baseline", Map.of(
            "stop_demand_statistics_rows_before_activation", 0,
            "evidence", "user-provided read-only transaction"));
        root.put("generations", generations.stream().map(Generation::toMap).toList());
        root.put("totals", Map.of(
            "generations", generations.size(),
            "rows", generations.stream().mapToLong(Generation::rowCount).sum()));
        root.put("privacy", Map.of(
            "contains_observation_rows", false,
            "contains_vehicle_or_plate_values", false,
            "contains_credentials", false));
        return root;
    }

    private static void persistFreeze(
        Connection connection,
        UUID freezeId,
        CutoverBoundary boundary,
        String manifestSha256,
        List<Generation> generations
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE training_model_release_exclusion SET final_cutover_at = ?
            WHERE release_id = ? AND bundle_digest = ? AND final_cutover_at IS NULL
            """)) {
            update.setObject(1, offset(boundary.finalCutoverAt()));
            update.setString(2, TEMPORARY_RELEASE_ID);
            update.setString(3, TEMPORARY_BUNDLE_DIGEST);
            if (update.executeUpdate() != 1) {
                throw new MigrationException("TEMP_CUTOVER_WINDOW_ALREADY_FROZEN");
            }
        }
        try (PreparedStatement parent = connection.prepareStatement("""
            INSERT INTO temporary_statistics_generation_freeze (
                id, release_id, bundle_digest, cutover_at, manifest_sha256,
                generation_count, row_count, status, observation_batch_high_water)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'FROZEN', ?)
            """)) {
            parent.setObject(1, freezeId);
            parent.setString(2, TEMPORARY_RELEASE_ID);
            parent.setString(3, TEMPORARY_BUNDLE_DIGEST);
            parent.setObject(4, offset(boundary.finalCutoverAt()));
            parent.setString(5, manifestSha256);
            parent.setInt(6, generations.size());
            parent.setLong(7, generations.stream().mapToLong(Generation::rowCount).sum());
            parent.setLong(8, boundary.observationBatchHighWater());
            parent.executeUpdate();
        }
        try (PreparedStatement row = connection.prepareStatement("""
            INSERT INTO training_statistics_generation_exclusion (
                freeze_id, release_id, bundle_digest, route_version_id, calculation_version,
                revision, data_until, computed_at, frozen_cell_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            for (Generation generation : generations) {
                row.setObject(1, freezeId);
                row.setString(2, TEMPORARY_RELEASE_ID);
                row.setString(3, TEMPORARY_BUNDLE_DIGEST);
                row.setLong(4, generation.routeVersionId());
                row.setString(5, generation.calculationVersion());
                row.setInt(6, generation.revision());
                row.setObject(7, offset(generation.dataUntil()));
                row.setObject(8, offset(generation.computedAt()));
                row.setInt(9, generation.rowCount());
                row.addBatch();
            }
            row.executeBatch();
        }
    }

    private static FormalDeployment requireFormalReplacement(
        Connection connection,
        CutoverBoundary boundary
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, release_id, bundle_digest, calculation_version, activated_at,
                   predecessor_deployment_id
            FROM model_deployment
            WHERE state = 'ACTIVE' AND id <> 1
            """)) {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("FORMAL_ACTIVE_DEPLOYMENT_MISSING");
                }
                long id = rows.getLong("id");
                String releaseId = rows.getString("release_id");
                String bundleDigest = rows.getString("bundle_digest");
                String calculation = rows.getString("calculation_version");
                OffsetDateTime activated = rows.getObject("activated_at", OffsetDateTime.class);
                Long predecessor = rows.getObject("predecessor_deployment_id", Long.class);
                if (!FORMAL_RELEASE_ID.equals(releaseId) || !FORMAL_BUNDLE_DIGEST.equals(bundleDigest)
                    || !CARRIER_CALCULATION_VERSION.equals(calculation) || activated == null
                    || activated.toInstant().isBefore(boundary.finalCutoverAt())
                    || predecessor == null || predecessor != TEMPORARY_DEPLOYMENT_ID || rows.next()) {
                    throw new MigrationException("FORMAL_DEPLOYMENT_IDENTITY_INVALID");
                }
                if (id == TEMPORARY_DEPLOYMENT_ID) {
                    throw new MigrationException("FORMAL_DEPLOYMENT_IDENTITY_INVALID");
                }
                return new FormalDeployment(id, calculation, activated.toInstant());
            }
        }
    }

    static void requireFreezeMatchesBoundary(
        Connection connection,
        UUID freezeId,
        CutoverBoundary boundary,
        String requiredStatus
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT cutover_at, observation_batch_high_water, status
            FROM temporary_statistics_generation_freeze WHERE id = ?
            """)) {
            statement.setObject(1, freezeId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()
                    || !boundary.finalCutoverAt().equals(
                        rows.getObject("cutover_at", OffsetDateTime.class).toInstant())
                    || boundary.observationBatchHighWater()
                        != rows.getLong("observation_batch_high_water")
                    || requiredStatus != null && !requiredStatus.equals(rows.getString("status"))) {
                    throw new MigrationException("TEMP_FREEZE_CUTOVER_BOUNDARY_MISMATCH");
                }
            }
        }
    }

    private static UUID latestFreezeId(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM temporary_statistics_generation_freeze
            WHERE release_id = ? AND bundle_digest = ? AND status IN ('FROZEN', 'CLEANED')
            ORDER BY frozen_at DESC LIMIT 1
            """)) {
            statement.setString(1, TEMPORARY_RELEASE_ID);
            statement.setString(2, TEMPORARY_BUNDLE_DIGEST);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("TEMP_GENERATION_FREEZE_MISSING");
                }
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private static long exactStatisticsCount(Connection connection, UUID freezeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*)
            FROM stop_demand_statistics statistics
            JOIN training_statistics_generation_exclusion excluded
              ON excluded.route_version_id = statistics.route_version_id
             AND excluded.calculation_version = statistics.calculation_version
             AND excluded.revision = statistics.revision
             AND excluded.data_until = statistics.data_until
             AND excluded.computed_at = statistics.computed_at
            WHERE excluded.freeze_id = ?
            """)) {
            statement.setObject(1, freezeId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long deleteForecastChunk(ImportSettings settings, int limit) {
        return DatabaseConnections.transaction(settings.database(), connection -> {
            SqlSafety.setLocalTimeouts(connection, settings);
            requireControlPaused(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                WITH target AS (
                    SELECT ctid FROM seat_forecast WHERE model_deployment_id = 1 LIMIT ?)
                DELETE FROM seat_forecast forecast USING target
                WHERE forecast.ctid = target.ctid
                """)) {
                statement.setInt(1, limit);
                return (long) statement.executeUpdate();
            }
        });
    }

    private static long deleteStatisticsChunk(
        ImportSettings settings,
        UUID freezeId,
        int limit
    ) {
        return DatabaseConnections.transaction(settings.database(), connection -> {
            SqlSafety.setLocalTimeouts(connection, settings);
            requireControlPaused(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                WITH target AS (
                    SELECT statistics.ctid
                    FROM stop_demand_statistics statistics
                    JOIN training_statistics_generation_exclusion excluded
                      ON excluded.route_version_id = statistics.route_version_id
                     AND excluded.calculation_version = statistics.calculation_version
                     AND excluded.revision = statistics.revision
                     AND excluded.data_until = statistics.data_until
                     AND excluded.computed_at = statistics.computed_at
                    WHERE excluded.freeze_id = ?
                    LIMIT ?)
                DELETE FROM stop_demand_statistics statistics USING target
                WHERE statistics.ctid = target.ctid
                """)) {
                statement.setObject(1, freezeId);
                statement.setInt(2, limit);
                return (long) statement.executeUpdate();
            }
        });
    }

    private static void requireCleanupEmpty(Connection connection, UUID freezeId) throws SQLException {
        if (scalar(connection, "SELECT count(*) FROM seat_forecast WHERE model_deployment_id = 1") != 0
            || exactStatisticsCount(connection, freezeId) != 0) {
            throw new MigrationException("TEMP_CLEANUP_TARGET_REMAINS");
        }
    }

    private static void markFreezeCleaned(Connection connection, UUID freezeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE temporary_statistics_generation_freeze
            SET status = 'CLEANED', cleaned_at = COALESCE(cleaned_at, clock_timestamp())
            WHERE id = ? AND status IN ('FROZEN', 'CLEANED')
            """)) {
            statement.setObject(1, freezeId);
            if (statement.executeUpdate() != 1) {
                throw new MigrationException("TEMP_GENERATION_FREEZE_STATE_CHANGED");
            }
        }
    }

    private static ObservationSnapshot observationSnapshot(
        Connection connection,
        long requestedHighWater
    ) throws SQLException {
        long highWater = requestedHighWater == Long.MAX_VALUE
            ? scalar(connection, "SELECT COALESCE(max(id), 0) FROM observation_batch") : requestedHighWater;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT
                count(*) AS batches,
                (SELECT count(*) FROM vehicle_observation observation
                 JOIN observation_batch batch ON batch.id = observation.observation_batch_id
                 WHERE batch.id <= ?) AS observations,
                min(response_received_at) AS response_from,
                max(response_received_at) AS response_through,
                count(*) FILTER (WHERE forecast_completed_at IS NULL) AS forecast_incomplete
            FROM observation_batch WHERE id <= ?
            """)) {
            statement.setLong(1, highWater);
            statement.setLong(2, highWater);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                OffsetDateTime from = rows.getObject("response_from", OffsetDateTime.class);
                OffsetDateTime through = rows.getObject("response_through", OffsetDateTime.class);
                return new ObservationSnapshot(
                    highWater, rows.getLong("batches"), rows.getLong("observations"),
                    from == null ? null : from.toInstant(), through == null ? null : through.toInstant(),
                    rows.getLong("forecast_incomplete"));
            }
        }
    }

    private static void requireObservationUnchanged(
        ObservationSnapshot before,
        ObservationSnapshot after
    ) {
        if (!before.equals(after)) {
            throw new MigrationException("TEMP_CLEANUP_OBSERVATION_INVARIANT_FAILED");
        }
    }

    private static Map<String, Object> cleanupReceipt(
        boolean execute,
        CleanupPlan plan,
        ObservationSnapshot after,
        long deletedForecasts,
        long deletedStatistics
    ) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "salmonbus-temp-release-cleanup-v1");
        root.put("mode", execute ? "EXECUTED" : "DRY_RUN");
        root.put("freezeId", plan.freezeId().toString());
        root.put("targetForecastRows", plan.targetForecastRows());
        root.put("targetStatisticsRows", plan.targetStatisticsRows());
        root.put("deletedForecastRows", deletedForecasts);
        root.put("deletedStatisticsRows", deletedStatistics);
        root.put("observationBefore", observationMap(plan.before()));
        root.put("observationAfter", observationMap(after));
        root.put("observationInvariant", plan.before().equals(after));
        root.put("deploymentRetained", true);
        root.put("forecastCompletedAtUpdated", false);
        return root;
    }

    private static Map<String, Object> observationMap(
        ObservationSnapshot snapshot
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("highWaterBatchId", snapshot.highWaterBatchId());
        value.put("batchRows", snapshot.batchRows());
        value.put("observationRows", snapshot.observationRows());
        value.put("responseFrom", snapshot.responseFrom());
        value.put("responseThrough", snapshot.responseThrough());
        value.put("forecastIncompleteRows", snapshot.forecastIncompleteRows());
        return value;
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static long scalar(
        Connection connection,
        String sql,
        Object first,
        Object second
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, first);
            statement.setObject(2, second);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void throttle(int milliseconds) {
        if (milliseconds == 0) {
            return;
        }
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MigrationException("TEMP_CLEANUP_THROTTLE_INTERRUPTED", e);
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record FreezeResult(
        UUID freezeId,
        boolean executed,
        Instant finalCutoverAt,
        long observationBatchHighWater,
        String manifestSha256,
        long generationCount,
        long rowCount,
        Map<String, Object> manifest
    ) {
    }

    public record PauseResult(
        UUID cutoverId,
        Instant pausedAt,
        long observationBatchHighWater
    ) {
    }

    public record CleanupResult(
        boolean executed,
        UUID freezeId,
        long targetForecastRows,
        long targetStatisticsRows,
        long deletedForecastRows,
        long deletedStatisticsRows,
        ObservationSnapshot observationBefore,
        ObservationSnapshot observationAfter,
        Map<String, Object> receipt
    ) {
    }

    public record ObservationSnapshot(
        long highWaterBatchId,
        long batchRows,
        long observationRows,
        Instant responseFrom,
        Instant responseThrough,
        long forecastIncompleteRows
    ) {
    }

    private record CleanupPlan(
        UUID freezeId,
        long targetForecastRows,
        long targetStatisticsRows,
        ObservationSnapshot before
    ) {
    }

    private record FormalDeployment(
        long id,
        String calculationVersion,
        Instant activatedAt
    ) {
    }

    record CutoverBoundary(
        Instant finalCutoverAt,
        long observationBatchHighWater
    ) {
    }

    private record Generation(
        long routeVersionId,
        String calculationVersion,
        int revision,
        Instant dataUntil,
        Instant computedAt,
        int rowCount
    ) {
        private Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("route_version_id", routeVersionId);
            value.put("calculation_version", calculationVersion);
            value.put("revision", revision);
            value.put("data_until", dataUntil.toString());
            value.put("computed_at", computedAt.toString());
            value.put("row_count", rowCount);
            return value;
        }
    }
}
