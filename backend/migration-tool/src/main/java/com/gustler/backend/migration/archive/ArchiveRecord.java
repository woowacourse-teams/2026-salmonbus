package com.gustler.backend.migration.archive;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.Sha256;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** audit v1에 최신 승인된 raw vehicle_id 예외만 추가한 private archive record. */
public record ArchiveRecord(
    Batch batch,
    List<NormalizedObservation> observations
) {

    public static final String SCHEMA_VERSION = "salmonbus-s3-rds-private-archive-record-v2";

    private static final Set<String> FIELDS = Set.of("batch", "observations", "schema_version");

    public ArchiveRecord {
        observations = List.copyOf(observations);
        if (observations.size() != batch.storedRows()) {
            throw new MigrationException("ARCHIVE_STORED_ROW_COUNT_MISMATCH");
        }
        Set<Integer> rowNumbers = new HashSet<>();
        Set<String> vehicleIds = new HashSet<>();
        for (NormalizedObservation observation : observations) {
            if (!rowNumbers.add(observation.sourceRowNumber())) {
                throw new MigrationException("ARCHIVE_DUPLICATE_SOURCE_ROW");
            }
            if (observation.vehicleId() != null && !vehicleIds.add(observation.vehicleId())) {
                throw new MigrationException("ARCHIVE_DUPLICATE_VEHICLE_IN_BATCH");
            }
        }
    }

    public byte[] canonicalBytes() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", SCHEMA_VERSION);
        value.put("batch", batch.toMap());
        value.put("observations", observations.stream().map(NormalizedObservation::toMap).toList());
        return CanonicalJson.bytesOf(value);
    }

    public String normalizedSha256() {
        return Sha256.of(canonicalBytes());
    }

    public static ArchiveRecord parseCanonical(
        byte[] bytes
    ) {
        JsonNode root = CanonicalJson.parse(bytes, "ARCHIVE_RECORD_JSON_INVALID");
        if (!CanonicalJson.stringOf(root).equals(new String(bytes, StandardCharsets.UTF_8))) {
            throw new MigrationException("ARCHIVE_RECORD_NOT_CANONICAL");
        }
        ArchiveJson.requireObjectWithFields(root, FIELDS, "ARCHIVE_RECORD_FIELDS_INVALID");
        if (!SCHEMA_VERSION.equals(ArchiveJson.text(root, "schema_version"))) {
            throw new MigrationException("ARCHIVE_RECORD_SCHEMA_INVALID");
        }
        List<NormalizedObservation> observations = new ArrayList<>();
        for (JsonNode observation : ArchiveJson.array(root, "observations")) {
            observations.add(NormalizedObservation.from(observation));
        }
        return new ArchiveRecord(Batch.from(ArchiveJson.object(root, "batch")), observations);
    }

    @Override
    public String toString() {
        return "ArchiveRecord[semanticBatchDigest=" + batch.semanticBatchDigest() + ", observations=***]";
    }

    public record Batch(
        String sourceAccount,
        String sourceSchemaVersion,
        UUID sourceRecordId,
        String semanticBatchDigest,
        String attemptKey,
        String routeName,
        String sourceRouteId,
        Instant scheduledAt,
        Instant requestedAt,
        Instant responseReceivedAt,
        int attemptNumber,
        Integer httpStatus,
        Integer resultCode,
        String outcome,
        String failureCode,
        int providerRows,
        int storedRows,
        int excludedRows,
        String normalizationVersion,
        String collectionStrategyVersion,
        String ingestionOrigin
    ) {

        private static final Set<String> FIELDS = Set.of(
            "attempt_key", "attempt_number", "collection_strategy_version", "excluded_rows",
            "failure_code", "http_status", "ingestion_origin", "normalization_version", "outcome",
            "provider_rows", "requested_at", "response_received_at", "result_code", "route_name",
            "scheduled_at", "semantic_batch_digest", "source_account", "source_record_id",
            "source_route_id", "source_schema_version", "stored_rows");
        private static final Set<String> OUTCOMES = Set.of(
            "UNKNOWN_AFTER_DISPATCH", "SUCCESS_ROWS", "SUCCESS_EMPTY",
            "FAILED_UPSTREAM", "FAILED_UNREADABLE");
        private static final Set<String> FAILURE_CODES = Set.of(
            "DAILY_QUOTA_EXCEEDED", "PER_SECOND_QUOTA_EXCEEDED", "UPSTREAM_ERROR");

        public Batch {
            if (!"827325854159".equals(sourceAccount)
                || !"1.0.0".equals(sourceSchemaVersion)
                || sourceRecordId == null
                || scheduledAt == null || requestedAt == null || responseReceivedAt == null
                || requestedAt.isBefore(scheduledAt) || responseReceivedAt.isBefore(requestedAt)) {
                throw new MigrationException("ARCHIVE_BATCH_IDENTITY_INVALID");
            }
            Sha256.requireDigest(semanticBatchDigest, "ARCHIVE_SEMANTIC_DIGEST_INVALID");
            if (!("s3v1:" + semanticBatchDigest).equals(attemptKey) || attemptNumber != 1) {
                throw new MigrationException("ARCHIVE_ATTEMPT_IDENTITY_INVALID");
            }
            if (!Set.of("1650", "3330").contains(routeName)
                || sourceRouteId == null || !sourceRouteId.matches("[0-9]{1,30}")) {
                throw new MigrationException("ARCHIVE_ROUTE_INVALID");
            }
            if (!OUTCOMES.contains(outcome)
                || (failureCode != null && !FAILURE_CODES.contains(failureCode))) {
                throw new MigrationException("ARCHIVE_BATCH_OUTCOME_INVALID");
            }
            if (providerRows < 0 || storedRows < 0 || excludedRows < 0
                || providerRows != storedRows + excludedRows) {
                throw new MigrationException("ARCHIVE_BATCH_ROW_COUNTS_INVALID");
            }
            if (!"normalization-v1.0.0-s3-backfill".equals(normalizationVersion)
                || collectionStrategyVersion == null
                || !collectionStrategyVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")
                || !"S3_BACKFILL".equals(ingestionOrigin)) {
                throw new MigrationException("ARCHIVE_BATCH_VERSION_INVALID");
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source_account", sourceAccount);
            value.put("source_schema_version", sourceSchemaVersion);
            value.put("source_record_id", sourceRecordId.toString());
            value.put("semantic_batch_digest", semanticBatchDigest);
            value.put("attempt_key", attemptKey);
            value.put("route_name", routeName);
            value.put("source_route_id", sourceRouteId);
            value.put("scheduled_at", scheduledAt.toString());
            value.put("requested_at", requestedAt.toString());
            value.put("response_received_at", responseReceivedAt.toString());
            value.put("attempt_number", attemptNumber);
            value.put("http_status", httpStatus);
            value.put("result_code", resultCode);
            value.put("outcome", outcome);
            value.put("failure_code", failureCode);
            value.put("provider_rows", providerRows);
            value.put("stored_rows", storedRows);
            value.put("excluded_rows", excludedRows);
            value.put("normalization_version", normalizationVersion);
            value.put("collection_strategy_version", collectionStrategyVersion);
            value.put("ingestion_origin", ingestionOrigin);
            return value;
        }

        static Batch from(
            JsonNode node
        ) {
            ArchiveJson.requireObjectWithFields(node, FIELDS, "ARCHIVE_BATCH_FIELDS_INVALID");
            try {
                return new Batch(
                    ArchiveJson.text(node, "source_account"),
                    ArchiveJson.text(node, "source_schema_version"),
                    UUID.fromString(ArchiveJson.text(node, "source_record_id")),
                    ArchiveJson.text(node, "semantic_batch_digest"),
                    ArchiveJson.text(node, "attempt_key"),
                    ArchiveJson.text(node, "route_name"),
                    ArchiveJson.text(node, "source_route_id"),
                    Instant.parse(ArchiveJson.text(node, "scheduled_at")),
                    Instant.parse(ArchiveJson.text(node, "requested_at")),
                    Instant.parse(ArchiveJson.text(node, "response_received_at")),
                    ArchiveJson.integer(node, "attempt_number"),
                    ArchiveJson.nullableInteger(node, "http_status"),
                    ArchiveJson.nullableInteger(node, "result_code"),
                    ArchiveJson.text(node, "outcome"),
                    ArchiveJson.nullableText(node, "failure_code"),
                    ArchiveJson.integer(node, "provider_rows"),
                    ArchiveJson.integer(node, "stored_rows"),
                    ArchiveJson.integer(node, "excluded_rows"),
                    ArchiveJson.text(node, "normalization_version"),
                    ArchiveJson.text(node, "collection_strategy_version"),
                    ArchiveJson.text(node, "ingestion_origin"));
            } catch (RuntimeException e) {
                if (e instanceof MigrationException migration) {
                    throw migration;
                }
                throw new MigrationException("ARCHIVE_BATCH_VALUE_INVALID", e);
            }
        }
    }
}
