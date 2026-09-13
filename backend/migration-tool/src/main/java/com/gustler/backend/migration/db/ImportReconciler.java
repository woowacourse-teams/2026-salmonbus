package com.gustler.backend.migration.db;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.archive.ArchiveVerifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class ImportReconciler {

    public Result reconcile(
        ImportSettings settings,
        ArchiveVerifier.Verification verification
    ) {
        UUID importId = ImportIds.fromManifest(verification.manifestSha256());
        Result result = DatabaseConnections.transaction(settings.database(), connection -> {
            SqlSafety.setLocalTimeouts(connection, settings);
            requireMergeFinished(connection, importId);
            Counts counts = counts(connection, importId);
            requireTargetCounts(connection, importId, counts);
            Map<String, Map<String, Long>> perRouteDay = perRouteDay(connection, importId);
            Map<String, Continuity> continuity = continuity(connection, importId);
            DerivedExclusion exclusion = derivedExclusion(connection, importId);
            Map<String, Object> receipt = receipt(
                importId, verification, counts, perRouteDay, continuity, exclusion);
            String canonical = CanonicalJson.stringOf(receipt);
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE historical_import_batch
                SET status = 'COMPLETE', completed_at = now(), updated_at = now(),
                    reconciliation_receipt = CAST(? AS jsonb)
                WHERE id = ? AND status = 'MERGING'
                """)) {
                statement.setString(1, canonical);
                statement.setObject(2, importId);
                if (statement.executeUpdate() != 1) {
                    throw new MigrationException("IMPORT_RECONCILIATION_STATE_CHANGED");
                }
            }
            if (verification.manifest().terminalFreeze() != null) {
                sealTerminalDataset(connection, importId, verification);
            }
            return new Result(importId, counts, perRouteDay, continuity, exclusion, receipt);
        });
        if (settings.receiptOutput() != null) {
            SecureFiles.writeNew(settings.receiptOutput(), CanonicalJson.bytesOf(result.receipt()));
        }
        return result;
    }

    private static void sealTerminalDataset(
        Connection connection,
        UUID importId,
        ArchiveVerifier.Verification verification
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO historical_import_dataset_seal (
                terminal_manifest_sha256, terminal_freeze_receipt_sha256,
                terminal_import_batch_id)
            VALUES (?, ?, ?)
            ON CONFLICT (terminal_manifest_sha256) DO NOTHING
            """)) {
            statement.setString(1, verification.manifestSha256());
            statement.setString(
                2, verification.manifest().terminalFreeze().terminalReceiptSha256());
            statement.setObject(3, importId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT sealed.terminal_freeze_receipt_sha256, sealed.terminal_import_batch_id,
                   imported.inventory_sha256, imported.archive_kind, imported.status
            FROM historical_import_dataset_seal sealed
            JOIN historical_import_batch imported ON imported.id = sealed.terminal_import_batch_id
            WHERE sealed.terminal_manifest_sha256 = ?
            """)) {
            statement.setString(1, verification.manifestSha256());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()
                    || !verification.manifest().terminalFreeze().terminalReceiptSha256().equals(
                        rows.getString("terminal_freeze_receipt_sha256"))
                    || !importId.equals(rows.getObject("terminal_import_batch_id", UUID.class))
                    || !verification.manifest().terminalFreeze().finalInventorySha256().equals(
                        rows.getString("inventory_sha256"))
                    || !"TERMINAL_DELTA".equals(rows.getString("archive_kind"))
                    || !"COMPLETE".equals(rows.getString("status"))
                    || rows.next()) {
                    throw new MigrationException("TERMINAL_DATASET_SEAL_IDENTITY_MISMATCH");
                }
            }
        }
    }

    private static void requireMergeFinished(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT status,
                   (SELECT count(*) FROM historical_import_record
                    WHERE import_batch_id = ? AND status = 'STAGED') AS pending
            FROM historical_import_batch WHERE id = ?
            """)) {
            statement.setObject(1, importId);
            statement.setObject(2, importId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !"MERGING".equals(rows.getString("status"))
                    || rows.getLong("pending") != 0) {
                    throw new MigrationException("IMPORT_MERGE_NOT_FINISHED");
                }
            }
        }
    }

    private static Counts counts(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT
                count(*) FILTER (WHERE status = 'MERGED') AS merged_batches,
                COALESCE(sum(stored_rows) FILTER (WHERE status = 'MERGED'), 0) AS merged_observations,
                count(*) FILTER (WHERE status = 'DUPLICATE_IMPORT') AS duplicates,
                count(*) FILTER (WHERE status = 'LIVE_OVERLAP') AS overlaps,
                COALESCE(sum(stored_rows) FILTER (WHERE status = 'LIVE_OVERLAP'), 0) AS overlap_observations,
                count(*) FILTER (WHERE status = 'REJECTED') AS rejected
            FROM historical_import_record WHERE import_batch_id = ?
            """)) {
            statement.setObject(1, importId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new Counts(
                    rows.getLong("merged_batches"), rows.getLong("merged_observations"),
                    rows.getLong("duplicates"), rows.getLong("overlaps"),
                    rows.getLong("overlap_observations"), rows.getLong("rejected"));
            }
        }
    }

    private static void requireTargetCounts(
        Connection connection,
        UUID importId,
        Counts expected
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT
                (SELECT count(*) FROM observation_batch WHERE historical_import_batch_id = ?) AS batches,
                (SELECT count(*) FROM vehicle_observation observation
                 JOIN observation_batch batch ON batch.id = observation.observation_batch_id
                 WHERE batch.historical_import_batch_id = ?) AS observations,
                (SELECT count(*) FROM migration_source_record WHERE import_batch_id = ?) AS provenance,
                (SELECT count(*) FROM seat_forecast forecast
                 JOIN vehicle_observation observation ON observation.id = forecast.vehicle_observation_id
                 JOIN observation_batch batch ON batch.id = observation.observation_batch_id
                 WHERE batch.historical_import_batch_id = ?) AS imported_forecasts,
                (SELECT count(*) FROM observation_batch
                 WHERE historical_import_batch_id = ?
                   AND (ingestion_origin <> 'S3_BACKFILL' OR forecast_completed_at IS NOT NULL)) AS origin_violations
            """)) {
            for (int index = 1; index <= 5; index++) {
                statement.setObject(index, importId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong("batches") != expected.mergedBatches()
                    || rows.getLong("observations") != expected.mergedObservations()
                    || rows.getLong("provenance") != expected.mergedBatches()
                    || rows.getLong("imported_forecasts") != 0
                    || rows.getLong("origin_violations") != 0) {
                    throw new MigrationException("IMPORT_TARGET_COUNT_RECONCILIATION_FAILED");
                }
            }
        }
    }

    private static Map<String, Map<String, Long>> perRouteDay(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT staged.model_route,
                   (staged.response_received_at AT TIME ZONE 'Asia/Seoul')::date::text AS kst_date,
                   count(*) AS batches,
                   COALESCE(sum(staged.stored_rows), 0) AS observations
            FROM historical_import_stage_batch staged
            JOIN historical_import_record record
              ON record.import_batch_id = staged.import_batch_id
             AND record.semantic_batch_digest = staged.semantic_batch_digest
            WHERE staged.import_batch_id = ? AND record.status = 'MERGED'
            GROUP BY staged.model_route,
                     (staged.response_received_at AT TIME ZONE 'Asia/Seoul')::date
            ORDER BY staged.model_route, kst_date
            """)) {
            statement.setObject(1, importId);
            Map<String, Map<String, Long>> values = new TreeMap<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.put(
                        rows.getString("model_route") + ":" + rows.getString("kst_date"),
                        Map.of(
                            "batches", rows.getLong("batches"),
                            "observations", rows.getLong("observations")));
                }
            }
            return Map.copyOf(values);
        }
    }

    private static Map<String, Continuity> continuity(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            WITH imported AS (
                SELECT route.display_name AS model_route, max(batch.response_received_at) AS last_at
                FROM observation_batch batch
                JOIN route_version version ON version.id = batch.route_version_id
                JOIN route ON route.id = version.route_id
                WHERE batch.historical_import_batch_id = ?
                GROUP BY route.display_name
            ), live AS (
                SELECT route.display_name AS model_route, min(batch.response_received_at) AS first_at
                FROM observation_batch batch
                JOIN route_version version ON version.id = batch.route_version_id
                JOIN route ON route.id = version.route_id
                WHERE batch.ingestion_origin = 'LIVE'
                GROUP BY route.display_name
            ), shared AS (
                SELECT imported.model_route, count(DISTINCT history.vehicle_id) AS shared_vehicles
                FROM imported
                JOIN live USING (model_route)
                JOIN route ON route.display_name = imported.model_route
                JOIN route_version version ON version.route_id = route.id AND version.valid_to IS NULL
                JOIN observation_batch history_batch
                  ON history_batch.route_version_id = version.id
                 AND history_batch.ingestion_origin = 'S3_BACKFILL'
                 AND history_batch.response_received_at > imported.last_at - interval '30 minutes'
                JOIN vehicle_observation history
                  ON history.observation_batch_id = history_batch.id AND history.vehicle_id IS NOT NULL
                WHERE EXISTS (
                    SELECT 1 FROM observation_batch live_batch
                    JOIN vehicle_observation live_observation
                      ON live_observation.observation_batch_id = live_batch.id
                    WHERE live_batch.route_version_id = version.id
                      AND live_batch.ingestion_origin = 'LIVE'
                      AND live_batch.response_received_at < live.first_at + interval '30 minutes'
                      AND live_observation.vehicle_id = history.vehicle_id)
                GROUP BY imported.model_route
            )
            SELECT imported.model_route, imported.last_at, live.first_at,
                   extract(epoch FROM live.first_at - imported.last_at) AS gap_seconds,
                   COALESCE(shared.shared_vehicles, 0) AS shared_vehicles
            FROM imported JOIN live USING (model_route)
            LEFT JOIN shared USING (model_route)
            ORDER BY imported.model_route
            """)) {
            statement.setObject(1, importId);
            Map<String, Continuity> values = new TreeMap<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    double gap = rows.getDouble("gap_seconds");
                    if (gap < 0) {
                        throw new MigrationException("IMPORT_SOURCE_TARGET_TIME_OVERLAP");
                    }
                    values.put(rows.getString("model_route"), new Continuity(
                        rows.getObject("last_at", OffsetDateTime.class).toInstant(),
                        rows.getObject("first_at", OffsetDateTime.class).toInstant(),
                        gap,
                        rows.getLong("shared_vehicles")));
                }
            }
            return Map.copyOf(values);
        }
    }

    private static DerivedExclusion derivedExclusion(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT
                (SELECT count(*) FROM seat_forecast forecast
                 JOIN model_deployment deployment ON deployment.id = forecast.model_deployment_id
                 JOIN training_model_release_exclusion excluded
                   ON excluded.release_id = deployment.release_id
                  AND excluded.bundle_digest = deployment.bundle_digest) AS excluded_forecasts,
                (SELECT count(*) FROM training_eligible_seat_forecast) AS eligible_forecasts,
                (SELECT count(*) FROM stop_demand_statistics statistics
                 WHERE NOT EXISTS (
                     SELECT 1 FROM training_eligible_stop_demand_statistics eligible
                     WHERE eligible.route_version_id = statistics.route_version_id
                       AND eligible.stop_order = statistics.stop_order
                       AND eligible.time_slot = statistics.time_slot
                       AND eligible.calculation_version = statistics.calculation_version
                       AND eligible.revision = statistics.revision)) AS excluded_statistics,
                (SELECT count(*) FROM vehicle_observation observation
                 JOIN observation_batch batch ON batch.id = observation.observation_batch_id
                 WHERE batch.historical_import_batch_id = ?) AS retained_import_observations
            """)) {
            statement.setObject(1, importId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new DerivedExclusion(
                    rows.getLong("excluded_forecasts"), rows.getLong("eligible_forecasts"),
                    rows.getLong("excluded_statistics"), rows.getLong("retained_import_observations"));
            }
        }
    }

    private static Map<String, Object> receipt(
        UUID importId,
        ArchiveVerifier.Verification verification,
        Counts counts,
        Map<String, Map<String, Long>> perRouteDay,
        Map<String, Continuity> continuity,
        DerivedExclusion exclusion
    ) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "salmonbus-historical-import-reconciliation-v1");
        root.put("importBatchId", importId.toString());
        root.put("manifestSha256", verification.manifestSha256());
        root.put("sourceCutoffAt", verification.manifest().cutoffAt().toString());
        root.put("terminalDatasetSeal", verification.manifest().terminalFreeze() != null);
        root.put("terminalFreezeReceiptSha256", verification.manifest().terminalFreeze() == null
            ? null : verification.manifest().terminalFreeze().terminalReceiptSha256());
        root.put("terminalFinalInventorySha256", verification.manifest().terminalFreeze() == null
            ? null : verification.manifest().terminalFreeze().finalInventorySha256());
        root.put("terminalPartitionInventorySha256", verification.manifest().terminalFreeze() == null
            ? null : verification.manifest().terminalFreeze().terminalPartitionInventorySha256());
        root.put("immutableBaseInventorySha256", verification.manifest().terminalFreeze() == null
            ? null : verification.manifest().terminalFreeze().immutableBaseInventorySha256());
        root.put("sourceClosureSha256", verification.manifest().terminalFreeze() == null
            ? null : verification.manifest().terminalFreeze().sourceClosureSha256());
        root.put("counts", counts);
        root.put("perRouteDay", perRouteDay);
        root.put("continuity", continuity);
        root.put("temporaryReleaseExclusion", exclusion);
        root.put("privacy", Map.of(
            "vehicleValuesEmitted", false,
            "plateValuesEmitted", false,
            "credentialsEmitted", false,
            "rawResponsesEmitted", false));
        return root;
    }

    public record Result(
        UUID importBatchId,
        Counts counts,
        Map<String, Map<String, Long>> perRouteDay,
        Map<String, Continuity> continuity,
        DerivedExclusion derivedExclusion,
        Map<String, Object> receipt
    ) {
    }

    public record Counts(
        long mergedBatches,
        long mergedObservations,
        long duplicateBatches,
        long overlapBatches,
        long overlapObservations,
        long rejectedBatches
    ) {
    }

    public record Continuity(
        java.time.Instant lastHistoricalAt,
        java.time.Instant firstLiveAt,
        double gapSeconds,
        long sharedVehicleCount
    ) {
    }

    public record DerivedExclusion(
        long excludedTemporaryForecasts,
        long eligibleForecasts,
        long excludedTemporaryStatisticsRows,
        long retainedImportedObservations
    ) {
    }
}
