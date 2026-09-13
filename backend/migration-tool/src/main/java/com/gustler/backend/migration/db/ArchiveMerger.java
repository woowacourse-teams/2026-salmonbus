package com.gustler.backend.migration.db;

import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.archive.ArchiveVerifier;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ArchiveMerger {

    private static final String INSERT_BATCH = """
        INSERT INTO observation_batch (
            route_version_id, scheduled_at, attempt_number, attempt_key,
            requested_at, response_received_at, forecast_completed_at, completed_at,
            http_status, result_code, outcome, failure_code, provider_rows, stored_rows,
            excluded_rows, normalization_version, collection_strategy_version,
            ingestion_origin, historical_import_batch_id, semantic_batch_digest,
            normalized_record_sha256)
        VALUES (?, ?, 1, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                'S3_BACKFILL', ?, ?, ?)
        ON CONFLICT DO NOTHING
        RETURNING id
        """;
    private static final String INSERT_OBSERVATION = """
        INSERT INTO vehicle_observation (
            observation_batch_id, route_version_id, source_row_number,
            vehicle_id, vehicle_trip_key, plate_number, stop_order, stop_id,
            passed_stop_order, running_state, remaining_seats, seat_unknown_reason,
            crowd_level, vehicle_type, route_type, tagless)
        VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final FailureInjector failureInjector;

    public ArchiveMerger() {
        this(FailureInjector.never());
    }

    ArchiveMerger(
        FailureInjector failureInjector
    ) {
        this.failureInjector = failureInjector;
    }

    public Result merge(
        ImportSettings settings,
        ArchiveVerifier.Verification verification
    ) {
        UUID importId = ImportIds.fromManifest(verification.manifestSha256());
        markMerging(settings, importId);
        int transactions = 0;
        long insertedBatches = 0;
        long insertedObservations = 0;
        long duplicates = 0;
        while (true) {
            ChunkResult chunk = DatabaseConnections.transaction(settings.database(), connection -> {
                SqlSafety.setLocalTimeouts(connection, settings);
                List<BatchRow> batches = selectChunk(connection, settings, importId);
                if (batches.isEmpty()) {
                    return ChunkResult.empty();
                }
                Map<String, List<ObservationRow>> observations = observationsOf(connection, importId, batches);
                long chunkBatches = 0;
                long chunkObservations = 0;
                long chunkDuplicates = 0;
                for (BatchRow batch : batches) {
                    Long batchId = insertBatch(connection, importId, batch);
                    if (batchId == null) {
                        classifyConcurrentDuplicate(connection, importId, batch);
                        chunkDuplicates++;
                        continue;
                    }
                    List<ObservationRow> rows = observations.getOrDefault(batch.semanticDigest(), List.of());
                    insertObservations(connection, settings, batchId, batch.routeVersionId(), rows);
                    insertSourceProvenance(
                        connection, importId, verification.manifestSha256(), batchId, batch);
                    markMerged(connection, importId, batch.semanticDigest(), batchId);
                    chunkBatches++;
                    chunkObservations += rows.size();
                }
                updateProgress(connection, importId);
                return new ChunkResult(chunkBatches, chunkObservations, chunkDuplicates, false);
            });
            if (chunk.done()) {
                break;
            }
            transactions++;
            insertedBatches += chunk.insertedBatches();
            insertedObservations += chunk.insertedObservations();
            duplicates += chunk.duplicates();
            failureInjector.afterCommit(transactions);
            throttle(settings.throttleMillis());
        }
        return new Result(importId, insertedBatches, insertedObservations, duplicates, transactions);
    }

    private static void markMerging(
        ImportSettings settings,
        UUID importId
    ) {
        DatabaseConnections.transaction(settings.database(), connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE historical_import_batch
                SET status = 'MERGING', updated_at = now()
                WHERE id = ? AND status IN ('VALIDATED', 'MERGING')
                """)) {
                statement.setObject(1, importId);
                if (statement.executeUpdate() != 1) {
                    throw new MigrationException("IMPORT_NOT_VALIDATED");
                }
            }
            return null;
        });
    }

    private static List<BatchRow> selectChunk(
        Connection connection,
        ImportSettings settings,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT staged.source_account, staged.source_record_id, staged.source_schema_version,
                   staged.semantic_batch_digest, staged.model_route, staged.scheduled_at,
                   staged.requested_at, staged.response_received_at, staged.attempt_key,
                   staged.http_status, staged.result_code, staged.outcome, staged.failure_code,
                   staged.provider_rows, staged.stored_rows, staged.excluded_rows,
                   staged.normalization_version, staged.collection_strategy_version,
                   record.normalized_record_sha256, binding.route_version_id
            FROM historical_import_record record
            JOIN historical_import_stage_batch staged
              ON staged.import_batch_id = record.import_batch_id
             AND staged.semantic_batch_digest = record.semantic_batch_digest
            JOIN historical_import_route_binding binding
              ON binding.import_batch_id = staged.import_batch_id
             AND binding.model_route = staged.model_route
            WHERE record.import_batch_id = ? AND record.status = 'STAGED'
            ORDER BY staged.response_received_at, staged.semantic_batch_digest
            LIMIT ?
            FOR UPDATE OF record SKIP LOCKED
            """)) {
            statement.setObject(1, importId);
            statement.setInt(2, settings.batchRecords());
            List<BatchRow> selected = new ArrayList<>();
            int observationRows = 0;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int next = rows.getInt("stored_rows");
                    if (!selected.isEmpty()
                        && observationRows + next > settings.maxTransactionObservationRows()) {
                        break;
                    }
                    if (next > settings.maxTransactionObservationRows()) {
                        throw new MigrationException("SINGLE_BATCH_EXCEEDS_TRANSACTION_ROW_LIMIT");
                    }
                    selected.add(batchRow(rows));
                    observationRows += next;
                }
            }
            return selected;
        }
    }

    private static BatchRow batchRow(
        ResultSet rows
    ) throws SQLException {
        return new BatchRow(
            rows.getString("source_account"),
            rows.getObject("source_record_id", UUID.class),
            rows.getString("source_schema_version"),
            rows.getString("semantic_batch_digest"),
            rows.getString("model_route"),
            rows.getObject("scheduled_at", OffsetDateTime.class),
            rows.getObject("requested_at", OffsetDateTime.class),
            rows.getObject("response_received_at", OffsetDateTime.class),
            rows.getString("attempt_key"),
            rows.getObject("http_status", Integer.class),
            rows.getObject("result_code", Integer.class),
            rows.getString("outcome"),
            rows.getString("failure_code"),
            rows.getInt("provider_rows"),
            rows.getInt("stored_rows"),
            rows.getInt("excluded_rows"),
            rows.getString("normalization_version"),
            rows.getString("collection_strategy_version"),
            rows.getString("normalized_record_sha256"),
            rows.getLong("route_version_id"));
    }

    private static Map<String, List<ObservationRow>> observationsOf(
        Connection connection,
        UUID importId,
        List<BatchRow> batches
    ) throws SQLException {
        String[] digests = batches.stream().map(BatchRow::semanticDigest).toArray(String[]::new);
        Array values = connection.createArrayOf("varchar", digests);
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT semantic_batch_digest, source_row_number, vehicle_id, stop_order, stop_id,
                   passed_stop_order, running_state, remaining_seats, seat_unknown_reason,
                   crowd_level, vehicle_type, route_type, tagless
            FROM historical_import_stage_observation
            WHERE import_batch_id = ? AND semantic_batch_digest = ANY (?)
            ORDER BY semantic_batch_digest, source_row_number
            """)) {
            statement.setObject(1, importId);
            statement.setArray(2, values);
            Map<String, List<ObservationRow>> result = new LinkedHashMap<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.computeIfAbsent(rows.getString("semantic_batch_digest"), ignored -> new ArrayList<>())
                        .add(new ObservationRow(
                            rows.getInt("source_row_number"),
                            rows.getString("vehicle_id"),
                            rows.getInt("stop_order"),
                            rows.getString("stop_id"),
                            rows.getInt("passed_stop_order"),
                            rows.getInt("running_state"),
                            rows.getObject("remaining_seats", Integer.class),
                            rows.getString("seat_unknown_reason"),
                            rows.getObject("crowd_level", Integer.class),
                            rows.getObject("vehicle_type", Integer.class),
                            rows.getObject("route_type", Integer.class),
                            rows.getObject("tagless", Integer.class)));
                }
            }
            return result;
        } finally {
            values.free();
        }
    }

    private static Long insertBatch(
        Connection connection,
        UUID importId,
        BatchRow batch
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_BATCH)) {
            statement.setLong(1, batch.routeVersionId());
            statement.setObject(2, batch.scheduledAt());
            statement.setString(3, batch.attemptKey());
            statement.setObject(4, batch.requestedAt());
            statement.setObject(5, batch.responseReceivedAt());
            statement.setObject(6, batch.responseReceivedAt());
            nullableInteger(statement, 7, batch.httpStatus());
            nullableInteger(statement, 8, batch.resultCode());
            statement.setString(9, batch.outcome());
            nullableString(statement, 10, batch.failureCode());
            statement.setInt(11, batch.providerRows());
            statement.setInt(12, batch.storedRows());
            statement.setInt(13, batch.excludedRows());
            statement.setString(14, batch.normalizationVersion());
            statement.setString(15, batch.collectionStrategyVersion());
            statement.setObject(16, importId);
            statement.setString(17, batch.semanticDigest());
            statement.setString(18, batch.normalizedRecordSha256());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private static void insertObservations(
        Connection connection,
        ImportSettings settings,
        long batchId,
        long routeVersionId,
        List<ObservationRow> observations
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_OBSERVATION)) {
            int pending = 0;
            for (ObservationRow observation : observations) {
                statement.setLong(1, batchId);
                statement.setLong(2, routeVersionId);
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
                statement.addBatch();
                pending++;
                if (pending == settings.jdbcBatchRows()) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
    }

    private static void insertSourceProvenance(
        Connection connection,
        UUID importId,
        String archiveSha256,
        long batchId,
        BatchRow batch
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO migration_source_record (
                source_account, source_record_id, semantic_batch_digest, archive_sha256,
                import_batch_id, observation_batch_id, source_schema_version,
                source_collected_at, importer_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, batch.sourceAccount());
            statement.setObject(2, batch.sourceRecordId());
            statement.setString(3, batch.semanticDigest());
            statement.setString(4, archiveSha256);
            statement.setObject(5, importId);
            statement.setLong(6, batchId);
            statement.setString(7, batch.sourceSchemaVersion());
            statement.setObject(8, batch.responseReceivedAt());
            statement.setString(9, SourceImporterVersion.VALUE);
            statement.executeUpdate();
        }
    }

    private static void markMerged(
        Connection connection,
        UUID importId,
        String semanticDigest,
        long batchId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE historical_import_record
            SET status = 'MERGED', target_observation_batch_id = ?, merged_at = now()
            WHERE import_batch_id = ? AND semantic_batch_digest = ? AND status = 'STAGED'
            """)) {
            statement.setLong(1, batchId);
            statement.setObject(2, importId);
            statement.setString(3, semanticDigest);
            if (statement.executeUpdate() != 1) {
                throw new MigrationException("IMPORT_RECORD_STATE_CHANGED_CONCURRENTLY");
            }
        }
    }

    private static void classifyConcurrentDuplicate(
        Connection connection,
        UUID importId,
        BatchRow batch
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1 FROM migration_source_record
                WHERE source_account = ? AND source_record_id = ? AND semantic_batch_digest = ?)
            """)) {
            statement.setString(1, batch.sourceAccount());
            statement.setObject(2, batch.sourceRecordId());
            statement.setString(3, batch.semanticDigest());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (!rows.getBoolean(1)) {
                    throw new MigrationException("IMPORT_NATURAL_KEY_CONFLICT");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE historical_import_record
            SET status = 'DUPLICATE_IMPORT', reject_code = 'IDENTICAL_CONCURRENT_IMPORT'
            WHERE import_batch_id = ? AND semantic_batch_digest = ? AND status = 'STAGED'
            """)) {
            statement.setObject(1, importId);
            statement.setString(2, batch.semanticDigest());
            statement.executeUpdate();
        }
    }

    private static void updateProgress(
        Connection connection,
        UUID importId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE historical_import_batch imported
            SET inserted_batch_count = counts.batches,
                inserted_observation_count = counts.observations,
                duplicate_batch_count = counts.duplicates,
                updated_at = now()
            FROM (
                SELECT
                    count(*) FILTER (WHERE record.status = 'MERGED') AS batches,
                    COALESCE(sum(record.stored_rows) FILTER (WHERE record.status = 'MERGED'), 0) AS observations,
                    count(*) FILTER (WHERE record.status = 'DUPLICATE_IMPORT') AS duplicates
                FROM historical_import_record record WHERE record.import_batch_id = ?
            ) counts
            WHERE imported.id = ?
            """)) {
            statement.setObject(1, importId);
            statement.setObject(2, importId);
            statement.executeUpdate();
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

    private static void throttle(
        int milliseconds
    ) {
        if (milliseconds == 0) {
            return;
        }
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MigrationException("IMPORT_THROTTLE_INTERRUPTED", e);
        }
    }

    @FunctionalInterface
    interface FailureInjector {

        void afterCommit(int transactions);

        static FailureInjector never() {
            return ignored -> {
            };
        }
    }

    public record Result(
        UUID importBatchId,
        long insertedBatches,
        long insertedObservations,
        long duplicateBatches,
        int committedTransactions
    ) {
    }

    private record ChunkResult(
        long insertedBatches,
        long insertedObservations,
        long duplicates,
        boolean done
    ) {
        private static ChunkResult empty() {
            return new ChunkResult(0, 0, 0, true);
        }
    }

    private record BatchRow(
        String sourceAccount,
        UUID sourceRecordId,
        String sourceSchemaVersion,
        String semanticDigest,
        String modelRoute,
        OffsetDateTime scheduledAt,
        OffsetDateTime requestedAt,
        OffsetDateTime responseReceivedAt,
        String attemptKey,
        Integer httpStatus,
        Integer resultCode,
        String outcome,
        String failureCode,
        int providerRows,
        int storedRows,
        int excludedRows,
        String normalizationVersion,
        String collectionStrategyVersion,
        String normalizedRecordSha256,
        long routeVersionId
    ) {
    }

    private record ObservationRow(
        int sourceRowNumber,
        String vehicleId,
        int stopOrder,
        String stopId,
        int passedStopOrder,
        int runningState,
        Integer remainingSeats,
        String seatUnknownReason,
        Integer crowdLevel,
        Integer vehicleType,
        Integer routeType,
        Integer tagless
    ) {

        @Override
        public String toString() {
            return "ObservationRow[sourceRowNumber=" + sourceRowNumber + ", vehicleId=***]";
        }
    }
}
