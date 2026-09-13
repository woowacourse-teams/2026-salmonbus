package com.gustler.backend.migration.db;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ImportRollback {

    private final FailureInjector failureInjector;

    public ImportRollback() {
        this(FailureInjector.never());
    }

    ImportRollback(
        FailureInjector failureInjector
    ) {
        this.failureInjector = failureInjector;
    }

    public Result run(
        ImportSettings settings,
        String manifestSha256,
        boolean execute
    ) {
        Result result = DatabaseConnections.transaction(settings.database(), connection -> {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            if (!execute) {
                SqlSafety.setTransactionReadOnly(connection);
            }
            SqlSafety.setLocalTimeouts(connection, settings);
            UUID importId = importId(connection, manifestSha256);
            Snapshot before = snapshot(connection);
            if (execute) {
                failureInjector.afterSnapshot();
            }
            long forecastReferences = importedForecasts(connection, importId);
            if (forecastReferences != 0) {
                throw new MigrationException("ROLLBACK_IMPORTED_FORECAST_EXISTS");
            }
            long batches = importedBatches(connection, importId);
            long observations = importedObservations(connection, importId);
            if (!execute || isRolledBack(connection, importId)) {
                return result(importId, execute, batches, observations, before, before);
            }
            requireNoAppliedDeltaDependsOn(connection, manifestSha256);
            restoreRouteValidity(connection, importId);
            deleteImportRows(connection, importId);
            Snapshot after = snapshot(connection);
            requireLiveUnchanged(before, after);
            if (before.totalBatches() - after.totalBatches() != batches
                || before.totalObservations() - after.totalObservations() != observations) {
                throw new MigrationException("ROLLBACK_ROW_COUNT_MISMATCH");
            }
            markRolledBack(connection, importId, batches, observations, before, after);
            return result(importId, true, batches, observations, before, after);
        });
        if (settings.receiptOutput() != null) {
            SecureFiles.writeNew(settings.receiptOutput(), CanonicalJson.bytesOf(result.receipt()));
        }
        return result;
    }

    private static UUID importId(
        Connection connection,
        String manifestSha256
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM historical_import_batch WHERE manifest_sha256 = ?
            """)) {
            statement.setString(1, manifestSha256);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("ROLLBACK_IMPORT_NOT_FOUND");
                }
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private static long importedForecasts(Connection connection, UUID id) throws SQLException {
        return scalar(connection, """
            SELECT count(*) FROM seat_forecast forecast
            JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
            JOIN observation_batch batch ON batch.id = observation.observation_batch_id
            WHERE batch.historical_import_batch_id = ?
            """, id);
    }

    private static long importedBatches(Connection connection, UUID id) throws SQLException {
        return scalar(connection,
            "SELECT count(*) FROM observation_batch WHERE historical_import_batch_id = ?", id);
    }

    private static long importedObservations(Connection connection, UUID id) throws SQLException {
        return scalar(connection, """
            SELECT count(*) FROM vehicle_observation observation
            JOIN observation_batch batch ON batch.id = observation.observation_batch_id
            WHERE batch.historical_import_batch_id = ?
            """, id);
    }

    private static boolean isRolledBack(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT status = 'ROLLED_BACK' FROM historical_import_batch WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static void requireNoAppliedDeltaDependsOn(
        Connection connection,
        String manifestSha256
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1 FROM historical_import_batch
                WHERE previous_manifest_sha256 = ? AND status <> 'ROLLED_BACK')
            """)) {
            statement.setString(1, manifestSha256);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getBoolean(1)) {
                    throw new MigrationException("ROLLBACK_ACTIVE_DELTA_DEPENDS_ON_IMPORT");
                }
            }
        }
    }

    private static void restoreRouteValidity(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement("""
            SELECT route_version_id, original_valid_from, valid_from
            FROM historical_import_route_binding
            WHERE import_batch_id = ? AND mapping_kind = 'EXTENDED_CURRENT_ROUTE'
            FOR UPDATE
            """)) {
            read.setObject(1, importId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    long versionId = rows.getLong("route_version_id");
                    OffsetDateTime original = rows.getObject("original_valid_from", OffsetDateTime.class);
                    OffsetDateTime importedFrom = rows.getObject("valid_from", OffsetDateTime.class);
                    OffsetDateTime requiredByOthers = earliestOtherImport(connection, importId, versionId);
                    OffsetDateTime restoreTo = requiredByOthers == null || requiredByOthers.isAfter(original)
                        ? original : requiredByOthers;
                    try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE route_version SET valid_from = ?
                        WHERE id = ? AND valid_from = ? AND valid_to IS NULL
                        """)) {
                        update.setObject(1, restoreTo);
                        update.setLong(2, versionId);
                        update.setObject(3, importedFrom);
                        if (update.executeUpdate() != 1) {
                            throw new MigrationException("ROLLBACK_ROUTE_VALIDITY_CHANGED");
                        }
                    }
                }
            }
        }
    }

    private static OffsetDateTime earliestOtherImport(
        Connection connection,
        UUID importId,
        long routeVersionId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT min(binding.valid_from)
            FROM historical_import_route_binding binding
            JOIN historical_import_batch imported ON imported.id = binding.import_batch_id
            WHERE binding.route_version_id = ? AND binding.import_batch_id <> ?
              AND imported.status <> 'ROLLED_BACK'
            """)) {
            statement.setLong(1, routeVersionId);
            statement.setObject(2, importId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getObject(1, OffsetDateTime.class);
            }
        }
    }

    private static void deleteImportRows(Connection connection, UUID importId) throws SQLException {
        execute(connection,
            "DELETE FROM historical_import_dataset_seal WHERE terminal_import_batch_id = ?", importId);
        execute(connection, "DELETE FROM migration_source_record WHERE import_batch_id = ?", importId);
        execute(connection, """
            DELETE FROM vehicle_observation observation
            USING observation_batch batch
            WHERE observation.observation_batch_id = batch.id
              AND batch.historical_import_batch_id = ?
            """, importId);
        try (PreparedStatement clear = connection.prepareStatement("""
            UPDATE historical_import_record
            SET status = 'ROLLED_BACK', target_observation_batch_id = NULL,
                reject_code = 'IMPORT_ROLLED_BACK', merged_at = NULL
            WHERE import_batch_id = ? AND status = 'MERGED'
            """)) {
            clear.setObject(1, importId);
            clear.executeUpdate();
        }
        execute(connection, "DELETE FROM observation_batch WHERE historical_import_batch_id = ?", importId);
        execute(connection, "DELETE FROM historical_import_stage_observation WHERE import_batch_id = ?", importId);
        execute(connection, "DELETE FROM historical_import_stage_batch WHERE import_batch_id = ?", importId);
    }

    private static void markRolledBack(
        Connection connection,
        UUID importId,
        long batches,
        long observations,
        Snapshot before,
        Snapshot after
    ) throws SQLException {
        Map<String, Object> receipt = receipt(importId, true, batches, observations, before, after);
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE historical_import_batch
            SET status = 'ROLLED_BACK', rolled_back_at = now(), updated_at = now(),
                reconciliation_receipt = CAST(? AS jsonb)
            WHERE id = ?
            """)) {
            statement.setString(1, CanonicalJson.stringOf(receipt));
            statement.setObject(2, importId);
            statement.executeUpdate();
        }
    }

    private static Snapshot snapshot(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT
              (SELECT count(*) FROM observation_batch) AS total_batches,
              (SELECT count(*) FROM vehicle_observation) AS total_observations,
              (SELECT count(*) FROM observation_batch WHERE ingestion_origin = 'LIVE') AS live_batches,
              (SELECT count(*) FROM vehicle_observation observation
               JOIN observation_batch batch ON batch.id = observation.observation_batch_id
               WHERE batch.ingestion_origin = 'LIVE') AS live_observations,
              (SELECT min(response_received_at) FROM observation_batch WHERE ingestion_origin = 'LIVE') AS live_from,
              (SELECT max(response_received_at) FROM observation_batch WHERE ingestion_origin = 'LIVE') AS live_through
            """)) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                OffsetDateTime from = rows.getObject("live_from", OffsetDateTime.class);
                OffsetDateTime through = rows.getObject("live_through", OffsetDateTime.class);
                return new Snapshot(
                    rows.getLong("total_batches"), rows.getLong("total_observations"),
                    rows.getLong("live_batches"), rows.getLong("live_observations"),
                    from == null ? null : from.toInstant(), through == null ? null : through.toInstant());
            }
        }
    }

    private static void requireLiveUnchanged(Snapshot before, Snapshot after) {
        if (before.liveBatches() != after.liveBatches()
            || before.liveObservations() != after.liveObservations()
            || !java.util.Objects.equals(before.liveFrom(), after.liveFrom())
            || !java.util.Objects.equals(before.liveThrough(), after.liveThrough())) {
            throw new MigrationException("ROLLBACK_LIVE_OBSERVATION_CHANGED");
        }
    }

    private static Result result(
        UUID importId,
        boolean executed,
        long batches,
        long observations,
        Snapshot before,
        Snapshot after
    ) {
        return new Result(
            importId, executed, batches, observations, before, after,
            receipt(importId, executed, batches, observations, before, after));
    }

    private static Map<String, Object> receipt(
        UUID importId,
        boolean executed,
        long batches,
        long observations,
        Snapshot before,
        Snapshot after
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "salmonbus-historical-import-rollback-v1");
        value.put("importBatchId", importId.toString());
        value.put("mode", executed ? "EXECUTED" : "DRY_RUN");
        value.put("targetBatches", batches);
        value.put("targetObservations", observations);
        value.put("before", before);
        value.put("after", after);
        value.put("liveObservationInvariant", !executed || liveEquals(before, after));
        return value;
    }

    private static boolean liveEquals(
        Snapshot before,
        Snapshot after
    ) {
        return before.liveBatches() == after.liveBatches()
            && before.liveObservations() == after.liveObservations()
            && java.util.Objects.equals(before.liveFrom(), after.liveFrom())
            && java.util.Objects.equals(before.liveThrough(), after.liveThrough());
    }

    private static long scalar(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    public record Result(
        UUID importBatchId,
        boolean executed,
        long targetBatches,
        long targetObservations,
        Snapshot before,
        Snapshot after,
        Map<String, Object> receipt
    ) {
    }

    public record Snapshot(
        long totalBatches,
        long totalObservations,
        long liveBatches,
        long liveObservations,
        java.time.Instant liveFrom,
        java.time.Instant liveThrough
    ) {
    }

    interface FailureInjector {

        void afterSnapshot();

        static FailureInjector never() {
            return () -> {
            };
        }
    }
}
