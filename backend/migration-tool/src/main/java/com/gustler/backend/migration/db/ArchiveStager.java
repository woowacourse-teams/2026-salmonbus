package com.gustler.backend.migration.db;

import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.archive.ArchiveManifest;
import com.gustler.backend.migration.archive.ArchiveReader;
import com.gustler.backend.migration.archive.ArchiveRecord;
import com.gustler.backend.migration.archive.ArchiveVerifier;
import com.gustler.backend.migration.archive.NormalizedObservation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ArchiveStager {

    private static final String INSERT_RECORD = """
        INSERT INTO historical_import_record (
            import_batch_id, source_account, source_record_id, source_schema_version,
            semantic_batch_digest, normalized_record_sha256,
            shard_sha256, shard_line_number, model_route, source_route_id,
            source_collected_at, kst_date, provider_rows, stored_rows, excluded_rows, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                (CAST(? AS timestamptz) AT TIME ZONE 'Asia/Seoul')::date, ?, ?, ?, 'STAGED')
        ON CONFLICT (import_batch_id, semantic_batch_digest) DO NOTHING
        """;
    private static final String INSERT_STAGE_BATCH = """
        INSERT INTO historical_import_stage_batch (
            import_batch_id, source_account, source_record_id, source_schema_version,
            semantic_batch_digest, route_reference_version, model_route,
            source_route_id, scheduled_at, requested_at, response_received_at, attempt_key,
            http_status, result_code, outcome, failure_code, provider_rows, stored_rows,
            excluded_rows, normalization_version, collection_strategy_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (import_batch_id, semantic_batch_digest) DO NOTHING
        """;
    private static final String INSERT_STAGE_OBSERVATION = """
        INSERT INTO historical_import_stage_observation (
            import_batch_id, semantic_batch_digest, source_row_number, vehicle_id, stop_order,
            stop_id, passed_stop_order, running_state, remaining_seats, seat_unknown_reason,
            crowd_level, vehicle_type, route_type, tagless)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (import_batch_id, semantic_batch_digest, source_row_number) DO NOTHING
        """;

    private final FailureInjector failureInjector;

    public ArchiveStager() {
        this(FailureInjector.never());
    }

    ArchiveStager(
        FailureInjector failureInjector
    ) {
        this.failureInjector = failureInjector;
    }

    public Result stage(
        ImportSettings settings,
        ArchiveVerifier.Verification verification
    ) {
        if (!verification.manifest().complete()) {
            throw new MigrationException("ARCHIVE_INCOMPLETE_DATES_OR_REJECTS");
        }
        UUID importId = ImportIds.fromManifest(verification.manifestSha256());
        initialize(settings, verification, importId);
        Map<String, Integer> checkpoints = checkpoints(settings, importId);
        Buffer buffer = new Buffer(settings, verification.manifest(), importId, checkpoints);
        ArchiveReader.forEachRecord(
            settings.archiveDirectory(), verification.manifest(), buffer::accept);
        buffer.finish();
        requireStagedCounts(settings, verification, importId);
        return new Result(importId, verification.batchCount(), verification.observationCount(), buffer.commits);
    }

    private void initialize(
        ImportSettings settings,
        ArchiveVerifier.Verification verification,
        UUID importId
    ) {
        DatabaseConnections.transaction(settings.database(), connection -> {
            SqlSafety.setLocalTimeouts(connection, settings);
            requirePreviousManifest(connection, verification.manifest());
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO historical_import_batch (
                    id, manifest_sha256, archive_schema_version, archive_kind,
                    previous_manifest_sha256, terminal_freeze_receipt_sha256,
                    inventory_sha256, source_cutoff_at,
                    source_collected_from, source_collected_through, importer_version,
                    target_kind, target_authority_from_min, route_validity_policy, status,
                    expected_batch_count, expected_observation_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STAGING', ?, ?)
                ON CONFLICT (manifest_sha256) DO NOTHING
                """)) {
                statement.setObject(1, importId);
                statement.setString(2, verification.manifestSha256());
                statement.setString(3, ArchiveManifest.SCHEMA_VERSION);
                statement.setString(4, verification.manifest().archiveKind());
                nullableString(statement, 5, verification.manifest().previousManifestSha256());
                nullableString(statement, 6, verification.manifest().terminalFreeze() == null
                    ? null : verification.manifest().terminalFreeze().terminalReceiptSha256());
                statement.setString(7, verification.manifest().inventory().sha256());
                statement.setObject(8, offset(verification.manifest().cutoffAt()));
                nullableTime(statement, 9, verification.collectedFrom());
                nullableTime(statement, 10, verification.collectedThrough());
                statement.setString(11, verification.manifest().exporterVersion());
                statement.setString(12, settings.database().targetKind().name());
                statement.setObject(13, offset(settings.earliestTargetAuthorityFrom()));
                statement.setString(14, settings.routeValidityPolicy().name());
                statement.setLong(15, verification.batchCount());
                statement.setLong(16, verification.observationCount());
                statement.executeUpdate();
            }
            requireCompatibleExistingBatch(connection, settings, verification, importId);
            for (Map.Entry<String, java.time.Instant> authority : settings.targetAuthorityFrom().entrySet()) {
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO historical_import_route_boundary (
                        import_batch_id, model_route, target_authority_from)
                    VALUES (?, ?, ?)
                    ON CONFLICT (import_batch_id, model_route) DO NOTHING
                    """)) {
                    statement.setObject(1, importId);
                    statement.setString(2, authority.getKey());
                    statement.setObject(3, offset(authority.getValue()));
                    statement.executeUpdate();
                }
            }
            requireCompatibleRouteBoundaries(connection, settings, importId);
            for (ArchiveManifest.Shard shard : verification.manifest().shards()) {
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO historical_import_shard (
                        import_batch_id, shard_ordinal, kst_date, shard_sha256, compressed_bytes,
                        expected_batch_count, expected_observation_count)
                    VALUES (?, ?, CAST(? AS date), ?, ?, ?, ?)
                    ON CONFLICT (import_batch_id, shard_sha256) DO NOTHING
                    """)) {
                    statement.setObject(1, importId);
                    statement.setInt(2, shard.ordinal());
                    statement.setString(3, shard.kstDate());
                    statement.setString(4, shard.sha256());
                    statement.setLong(5, shard.compressedBytes());
                    statement.setInt(6, shard.batchCount());
                    statement.setLong(7, shard.observationCount());
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    private static void requireCompatibleRouteBoundaries(
        Connection connection,
        ImportSettings settings,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT model_route, target_authority_from
            FROM historical_import_route_boundary WHERE import_batch_id = ?
            """)) {
            statement.setObject(1, importId);
            Map<String, java.time.Instant> actual = new HashMap<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.put(
                        rows.getString(1), rows.getObject(2, OffsetDateTime.class).toInstant());
                }
            }
            if (!actual.equals(settings.targetAuthorityFrom())) {
                throw new MigrationException("IMPORT_ROUTE_AUTHORITY_RESUME_MISMATCH");
            }
        }
    }

    private static void requirePreviousManifest(
        Connection connection,
        ArchiveManifest manifest
    ) throws SQLException {
        if (manifest.previousManifestSha256() == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT archive_kind, inventory_sha256, status
            FROM historical_import_batch
            WHERE manifest_sha256 = ?
            """)) {
            statement.setString(1, manifest.previousManifestSha256());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !"COMPLETE".equals(rows.getString("status"))) {
                    throw new MigrationException("CATCHUP_PREVIOUS_IMPORT_NOT_COMPLETE");
                }
                if (manifest.terminalFreeze() != null
                    && (!"BASE".equals(rows.getString("archive_kind"))
                        || !manifest.terminalFreeze().immutableBaseInventorySha256().equals(
                            rows.getString("inventory_sha256")))) {
                    throw new MigrationException("TERMINAL_PREVIOUS_BASE_IDENTITY_MISMATCH");
                }
            }
        }
    }

    private static void requireCompatibleExistingBatch(
        Connection connection,
        ImportSettings settings,
        ArchiveVerifier.Verification verification,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, target_kind, target_authority_from_min, route_validity_policy, status,
                   expected_batch_count, expected_observation_count
            FROM historical_import_batch
            WHERE manifest_sha256 = ?
            """)) {
            statement.setString(1, verification.manifestSha256());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()
                    || !importId.equals(rows.getObject("id", UUID.class))
                    || !settings.database().targetKind().name().equals(rows.getString("target_kind"))
                    || !settings.earliestTargetAuthorityFrom().equals(
                        rows.getObject("target_authority_from_min", OffsetDateTime.class).toInstant())
                    || !settings.routeValidityPolicy().name().equals(rows.getString("route_validity_policy"))
                    || rows.getLong("expected_batch_count") != verification.batchCount()
                    || rows.getLong("expected_observation_count") != verification.observationCount()
                    || "ROLLED_BACK".equals(rows.getString("status"))) {
                    throw new MigrationException("IMPORT_RESUME_IDENTITY_MISMATCH");
                }
            }
        }
    }

    private static Map<String, Integer> checkpoints(
        ImportSettings settings,
        UUID importId
    ) {
        return DatabaseConnections.transaction(settings.database(), connection -> {
            Map<String, Integer> result = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT shard_sha256, staged_line_count
                FROM historical_import_shard
                WHERE import_batch_id = ?
                """)) {
                statement.setObject(1, importId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.put(rows.getString(1), rows.getInt(2));
                    }
                }
            }
            return result;
        });
    }

    private void write(
        ImportSettings settings,
        ArchiveManifest manifest,
        UUID importId,
        ArchiveManifest.Shard shard,
        List<ArchiveReader.IndexedRecord> lines
    ) {
        DatabaseConnections.transaction(settings.database(), connection -> {
            SqlSafety.setLocalTimeouts(connection, settings);
            try (PreparedStatement recordStatement = connection.prepareStatement(INSERT_RECORD);
                PreparedStatement batchStatement = connection.prepareStatement(INSERT_STAGE_BATCH);
                PreparedStatement observationStatement = connection.prepareStatement(INSERT_STAGE_OBSERVATION)) {
                int pendingObservations = 0;
                for (ArchiveReader.IndexedRecord indexed : lines) {
                    ArchiveRecord record = indexed.record();
                    bindRecord(recordStatement, importId, shard, indexed);
                    recordStatement.executeUpdate();
                    bindBatch(batchStatement, importId, record, rosterVersion(manifest, record));
                    batchStatement.executeUpdate();
                    for (NormalizedObservation observation : record.observations()) {
                        bindObservation(
                            observationStatement, importId, record.batch().semanticBatchDigest(), observation);
                        observationStatement.addBatch();
                        pendingObservations++;
                        if (pendingObservations == settings.jdbcBatchRows()) {
                            observationStatement.executeBatch();
                            pendingObservations = 0;
                        }
                    }
                }
                if (pendingObservations > 0) {
                    observationStatement.executeBatch();
                }
            }
            int lastLine = lines.getLast().lineNumber();
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE historical_import_shard
                SET staged_line_count = GREATEST(staged_line_count, ?), status = 'STAGING', updated_at = now()
                WHERE import_batch_id = ? AND shard_sha256 = ?
                """)) {
                statement.setInt(1, lastLine);
                statement.setObject(2, importId);
                statement.setString(3, shard.sha256());
                if (statement.executeUpdate() != 1) {
                    throw new MigrationException("IMPORT_SHARD_CHECKPOINT_MISSING");
                }
            }
            return null;
        });
    }

    private static void bindRecord(
        PreparedStatement statement,
        UUID importId,
        ArchiveManifest.Shard shard,
        ArchiveReader.IndexedRecord indexed
    ) throws SQLException {
        ArchiveRecord record = indexed.record();
        statement.setObject(1, importId);
        statement.setString(2, record.batch().sourceAccount());
        statement.setObject(3, record.batch().sourceRecordId());
        statement.setString(4, record.batch().sourceSchemaVersion());
        statement.setString(5, record.batch().semanticBatchDigest());
        statement.setString(6, indexed.normalizedSha256());
        statement.setString(7, shard.sha256());
        statement.setInt(8, indexed.lineNumber());
        statement.setString(9, record.batch().routeName());
        statement.setString(10, record.batch().sourceRouteId());
        statement.setObject(11, offset(record.batch().responseReceivedAt()));
        statement.setObject(12, offset(record.batch().responseReceivedAt()));
        statement.setInt(13, record.batch().providerRows());
        statement.setInt(14, record.batch().storedRows());
        statement.setInt(15, record.batch().excludedRows());
    }

    private static void bindBatch(
        PreparedStatement statement,
        UUID importId,
        ArchiveRecord record,
        String routeReferenceVersion
    ) throws SQLException {
        statement.setObject(1, importId);
        statement.setString(2, record.batch().sourceAccount());
        statement.setObject(3, record.batch().sourceRecordId());
        statement.setString(4, record.batch().sourceSchemaVersion());
        statement.setString(5, record.batch().semanticBatchDigest());
        statement.setString(6, routeReferenceVersion);
        statement.setString(7, record.batch().routeName());
        statement.setString(8, record.batch().sourceRouteId());
        statement.setObject(9, offset(record.batch().scheduledAt()));
        statement.setObject(10, offset(record.batch().requestedAt()));
        statement.setObject(11, offset(record.batch().responseReceivedAt()));
        statement.setString(12, record.batch().attemptKey());
        nullableInteger(statement, 13, record.batch().httpStatus());
        nullableInteger(statement, 14, record.batch().resultCode());
        statement.setString(15, record.batch().outcome());
        nullableString(statement, 16, record.batch().failureCode());
        statement.setInt(17, record.batch().providerRows());
        statement.setInt(18, record.batch().storedRows());
        statement.setInt(19, record.batch().excludedRows());
        statement.setString(20, record.batch().normalizationVersion());
        statement.setString(21, record.batch().collectionStrategyVersion());
    }

    private static void bindObservation(
        PreparedStatement statement,
        UUID importId,
        String recordDigest,
        NormalizedObservation observation
    ) throws SQLException {
        statement.setObject(1, importId);
        statement.setString(2, recordDigest);
        statement.setInt(3, observation.sourceRowNumber());
        nullableString(statement, 4, observation.vehicleId());
        statement.setInt(5, observation.stopOrder());
        statement.setString(6, observation.stopId());
        statement.setInt(7, observation.passedStopOrder());
        statement.setInt(8, observation.runningState());
        nullableInteger(statement, 9, observation.remainingSeats());
        nullableString(statement, 10, observation.seatUnknownReason());
        nullableInteger(statement, 11, observation.crowdLevel());
        nullableInteger(statement, 12, observation.vehicleType());
        nullableInteger(statement, 13, observation.routeType());
        nullableInteger(statement, 14, observation.tagless());
    }

    private static String rosterVersion(
        ArchiveManifest manifest,
        ArchiveRecord record
    ) {
        return com.gustler.backend.migration.archive.RouteRoster.forObservation(
            manifest.routeReferences(), record.batch().routeName(), record.batch().responseReceivedAt()).version();
    }

    private static void requireStagedCounts(
        ImportSettings settings,
        ArchiveVerifier.Verification verification,
        UUID importId
    ) {
        DatabaseConnections.transaction(settings.database(), connection -> {
            long batches = count(connection,
                "SELECT count(*) FROM historical_import_stage_batch WHERE import_batch_id = ?", importId);
            long observations = count(connection, """
                SELECT count(*) FROM historical_import_stage_observation WHERE import_batch_id = ?
                """, importId);
            if (batches != verification.batchCount() || observations != verification.observationCount()) {
                throw new MigrationException("IMPORT_STAGED_COUNT_MISMATCH");
            }
            try (PreparedStatement shards = connection.prepareStatement("""
                UPDATE historical_import_shard
                SET status = 'STAGED', updated_at = now()
                WHERE import_batch_id = ? AND staged_line_count = expected_batch_count
                """)) {
                shards.setObject(1, importId);
                shards.executeUpdate();
            }
            try (PreparedStatement batch = connection.prepareStatement("""
                UPDATE historical_import_batch
                SET status = 'STAGED', staged_batch_count = ?, staged_observation_count = ?, updated_at = now()
                WHERE id = ?
                """)) {
                batch.setLong(1, batches);
                batch.setLong(2, observations);
                batch.setObject(3, importId);
                batch.executeUpdate();
            }
            return null;
        });
    }

    private static long count(
        Connection connection,
        String sql,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, importId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static OffsetDateTime offset(
        java.time.Instant instant
    ) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static void nullableTime(
        PreparedStatement statement,
        int index,
        java.time.Instant value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setObject(index, offset(value));
        }
    }

    private static void nullableString(
        PreparedStatement statement,
        int index,
        String value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void nullableInteger(
        PreparedStatement statement,
        int index,
        Integer value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    @FunctionalInterface
    interface FailureInjector {

        void afterCommit(
            int commits
        );

        static FailureInjector never() {
            return commits -> {
            };
        }
    }

    public record Result(
        UUID importBatchId,
        long stagedBatches,
        long stagedObservations,
        int committedChunks
    ) {
    }

    private final class Buffer {

        private final ImportSettings settings;
        private final ArchiveManifest manifest;
        private final UUID importId;
        private final Map<String, Integer> checkpoints;
        private final List<ArchiveReader.IndexedRecord> lines = new ArrayList<>();
        private ArchiveManifest.Shard shard;
        private int commits;

        private Buffer(
            ImportSettings settings,
            ArchiveManifest manifest,
            UUID importId,
            Map<String, Integer> checkpoints
        ) {
            this.settings = settings;
            this.manifest = manifest;
            this.importId = importId;
            this.checkpoints = checkpoints;
        }

        private void accept(
            ArchiveManifest.Shard incoming,
            ArchiveReader.IndexedRecord record
        ) {
            if (shard != null && shard.ordinal() != incoming.ordinal()) {
                flush();
            }
            shard = incoming;
            if (record.lineNumber() <= checkpoints.getOrDefault(incoming.sha256(), 0)) {
                return;
            }
            lines.add(record);
            if (lines.size() == settings.batchRecords()) {
                flush();
            }
        }

        private void finish() {
            flush();
        }

        private void flush() {
            if (lines.isEmpty()) {
                return;
            }
            write(settings, manifest, importId, shard, List.copyOf(lines));
            int lastLine = lines.getLast().lineNumber();
            checkpoints.put(shard.sha256(), lastLine);
            lines.clear();
            commits++;
            failureInjector.afterCommit(commits);
        }
    }
}
