package com.gustler.backend.migration.db;

import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.archive.ArchiveManifest;
import com.gustler.backend.migration.archive.ArchiveVerifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class ArchiveImportValidator {

    public Result validate(
        ImportSettings settings,
        ArchiveVerifier.Verification verification
    ) {
        UUID importId = ImportIds.fromManifest(verification.manifestSha256());
        return DatabaseConnections.transaction(settings.database(), connection -> {
            SqlSafety.setLocalTimeouts(connection, settings);
            requireStageComplete(connection, importId, verification);
            Baseline baseline = baseline(connection);
            classifyTargetOverlap(connection, importId);
            classifyExistingImports(connection, importId);
            rejectIdentityConflicts(connection, importId);
            new RouteBinder().bind(connection, settings, verification.manifest(), importId);
            requireRouteContext(connection, importId);
            long overlaps = countStatus(connection, importId, "LIVE_OVERLAP");
            long duplicates = countStatus(connection, importId, "DUPLICATE_IMPORT");
            long ready = countStatus(connection, importId, "STAGED");
            storeBaselineAndValidate(connection, importId, baseline, overlaps, duplicates);
            return new Result(importId, ready, overlaps, duplicates, baseline);
        });
    }

    private static void requireStageComplete(
        Connection connection,
        UUID importId,
        ArchiveVerifier.Verification verification
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT status, staged_batch_count, staged_observation_count
            FROM historical_import_batch WHERE id = ?
            """)) {
            statement.setObject(1, importId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()
                    || !java.util.Set.of("STAGED", "VALIDATED").contains(rows.getString("status"))
                    || rows.getLong("staged_batch_count") != verification.batchCount()
                    || rows.getLong("staged_observation_count") != verification.observationCount()) {
                    throw new MigrationException("IMPORT_STAGE_NOT_COMPLETE");
                }
            }
        }
    }

    private static void classifyTargetOverlap(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE historical_import_record record
            SET status = 'LIVE_OVERLAP', reject_code = 'AT_OR_AFTER_TARGET_AUTHORITY'
            FROM historical_import_stage_batch staged, historical_import_route_boundary boundary
            WHERE record.import_batch_id = ?
              AND staged.import_batch_id = record.import_batch_id
              AND staged.semantic_batch_digest = record.semantic_batch_digest
              AND boundary.import_batch_id = record.import_batch_id
              AND boundary.model_route = staged.model_route
              AND staged.response_received_at >= boundary.target_authority_from
              AND record.status = 'STAGED'
            """)) {
            statement.setObject(1, importId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE historical_import_route_boundary boundary
            SET accepted_source_from = counts.accepted_from,
                accepted_source_through = counts.accepted_through,
                overlap_batch_count = counts.overlap_batches,
                overlap_observation_count = counts.overlap_observations
            FROM (
                SELECT staged.model_route,
                       MIN(staged.response_received_at) FILTER (
                           WHERE staged.response_received_at < route_boundary.target_authority_from) AS accepted_from,
                       MAX(staged.response_received_at) FILTER (
                           WHERE staged.response_received_at < route_boundary.target_authority_from) AS accepted_through,
                       COUNT(*) FILTER (
                           WHERE staged.response_received_at >= route_boundary.target_authority_from) AS overlap_batches,
                       COALESCE(SUM(staged.stored_rows) FILTER (
                           WHERE staged.response_received_at >= route_boundary.target_authority_from), 0)
                           AS overlap_observations
                FROM historical_import_stage_batch staged
                JOIN historical_import_route_boundary route_boundary
                  ON route_boundary.import_batch_id = staged.import_batch_id
                 AND route_boundary.model_route = staged.model_route
                WHERE staged.import_batch_id = ?
                GROUP BY staged.model_route
            ) counts
            WHERE boundary.import_batch_id = ? AND boundary.model_route = counts.model_route
            """)) {
            statement.setObject(1, importId);
            statement.setObject(2, importId);
            statement.executeUpdate();
        }
    }

    private static void classifyExistingImports(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE historical_import_record record
            SET status = 'DUPLICATE_IMPORT', reject_code = 'IDENTICAL_PRIOR_IMPORT'
            FROM historical_import_stage_batch staged
            WHERE record.import_batch_id = ?
              AND staged.import_batch_id = record.import_batch_id
              AND staged.semantic_batch_digest = record.semantic_batch_digest
              AND record.status = 'STAGED'
              AND EXISTS (
                  SELECT 1 FROM migration_source_record existing
                  WHERE existing.source_account = staged.source_account
                    AND existing.source_record_id = staged.source_record_id
                    AND existing.semantic_batch_digest = staged.semantic_batch_digest)
            """)) {
            statement.setObject(1, importId);
            statement.executeUpdate();
        }
    }

    private static void rejectIdentityConflicts(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1
                FROM historical_import_stage_batch staged
                JOIN historical_import_record record
                  ON record.import_batch_id = staged.import_batch_id
                 AND record.semantic_batch_digest = staged.semantic_batch_digest
                WHERE staged.import_batch_id = ? AND record.status = 'STAGED'
                  AND (
                    EXISTS (
                        SELECT 1 FROM migration_source_record existing
                        WHERE existing.source_account = staged.source_account
                          AND existing.source_record_id = staged.source_record_id
                          AND existing.semantic_batch_digest <> staged.semantic_batch_digest)
                    OR EXISTS (
                        SELECT 1 FROM migration_source_record existing
                        WHERE existing.source_account = staged.source_account
                          AND existing.semantic_batch_digest = staged.semantic_batch_digest
                          AND existing.source_record_id <> staged.source_record_id)))
            """)) {
            statement.setObject(1, importId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getBoolean(1)) {
                    throw new MigrationException("IMPORT_SOURCE_IDENTITY_CONFLICT");
                }
            }
        }
    }

    private static void requireRouteContext(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*)
            FROM historical_import_stage_observation observation
            JOIN historical_import_stage_batch batch
              ON batch.import_batch_id = observation.import_batch_id
             AND batch.semantic_batch_digest = observation.semantic_batch_digest
            JOIN historical_import_record record
              ON record.import_batch_id = batch.import_batch_id
             AND record.semantic_batch_digest = batch.semantic_batch_digest
            LEFT JOIN historical_import_route_binding binding
              ON binding.import_batch_id = batch.import_batch_id
             AND binding.model_route = batch.model_route
            LEFT JOIN route_stop stop
              ON stop.route_version_id = binding.route_version_id
             AND stop.stop_order = observation.stop_order
             AND stop.stop_id = observation.stop_id
            WHERE batch.import_batch_id = ? AND record.status = 'STAGED'
              AND (
                binding.route_version_id IS NULL
                OR NOT (batch.response_received_at >= binding.valid_from
                        AND batch.response_received_at < binding.valid_to)
                OR stop.route_version_id IS NULL)
            """)) {
            statement.setObject(1, importId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != 0) {
                    throw new MigrationException("IMPORT_ROUTE_CONTEXT_INVALID");
                }
            }
        }
    }

    private static Baseline baseline(
        Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT
                (SELECT count(*) FROM observation_batch) AS batches,
                (SELECT count(*) FROM vehicle_observation) AS observations,
                (SELECT count(*) FROM seat_forecast) AS forecasts,
                (SELECT count(*) FROM stop_demand_statistics) AS statistics,
                (SELECT count(*) FROM model_deployment) AS deployments,
                (SELECT min(response_received_at) FROM observation_batch) AS response_from,
                (SELECT max(response_received_at) FROM observation_batch) AS response_through
            """)) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                OffsetDateTime from = rows.getObject("response_from", OffsetDateTime.class);
                OffsetDateTime through = rows.getObject("response_through", OffsetDateTime.class);
                return new Baseline(
                    rows.getLong("batches"), rows.getLong("observations"), rows.getLong("forecasts"),
                    rows.getLong("statistics"), rows.getLong("deployments"),
                    from == null ? null : from.toInstant(), through == null ? null : through.toInstant());
            }
        }
    }

    private static long countStatus(
        Connection connection,
        UUID importId,
        String status
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT count(*) FROM historical_import_record WHERE import_batch_id = ? AND status = ?
            """)) {
            statement.setObject(1, importId);
            statement.setString(2, status);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void storeBaselineAndValidate(
        Connection connection,
        UUID importId,
        Baseline baseline,
        long overlaps,
        long duplicates
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE historical_import_batch
            SET status = 'VALIDATED', duplicate_batch_count = ?, rejected_batch_count = ?,
                baseline_observation_batch_count = COALESCE(baseline_observation_batch_count, ?),
                baseline_vehicle_observation_count = COALESCE(baseline_vehicle_observation_count, ?),
                baseline_seat_forecast_count = COALESCE(baseline_seat_forecast_count, ?),
                baseline_statistics_count = COALESCE(baseline_statistics_count, ?),
                baseline_model_deployment_count = COALESCE(baseline_model_deployment_count, ?),
                baseline_response_from = COALESCE(baseline_response_from, ?),
                baseline_response_through = COALESCE(baseline_response_through, ?),
                updated_at = now()
            WHERE id = ?
            """)) {
            statement.setLong(1, duplicates);
            statement.setLong(2, overlaps);
            statement.setLong(3, baseline.observationBatches());
            statement.setLong(4, baseline.vehicleObservations());
            statement.setLong(5, baseline.seatForecasts());
            statement.setLong(6, baseline.statistics());
            statement.setLong(7, baseline.modelDeployments());
            statement.setObject(8, baseline.responseFrom() == null ? null : baseline.responseFrom().atOffset(java.time.ZoneOffset.UTC));
            statement.setObject(9, baseline.responseThrough() == null ? null : baseline.responseThrough().atOffset(java.time.ZoneOffset.UTC));
            statement.setObject(10, importId);
            if (statement.executeUpdate() != 1) {
                throw new MigrationException("IMPORT_BATCH_MISSING");
            }
        }
    }

    public record Result(
        UUID importBatchId,
        long readyBatches,
        long overlapBatches,
        long duplicateBatches,
        Baseline baseline
    ) {
    }

    public record Baseline(
        long observationBatches,
        long vehicleObservations,
        long seatForecasts,
        long statistics,
        long modelDeployments,
        java.time.Instant responseFrom,
        java.time.Instant responseThrough
    ) {
    }
}
