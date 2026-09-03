package com.gustler.backend.migration.archive;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

public record ArchiveManifest(
    String archiveKind,
    String previousManifestSha256,
    String exporterVersion,
    Instant cutoffAt,
    Inventory inventory,
    String normalizationVersion,
    boolean complete,
    TerminalFreeze terminalFreeze,
    List<RouteRoster> routeReferences,
    List<Shard> shards,
    Summary summary
) {

    public static final String SCHEMA_VERSION = "salmonbus-s3-rds-private-archive-manifest-v2";
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String MANIFEST_DIGEST_FILE = "manifest.sha256";

    private static final Set<String> FIELDS = Set.of(
        "archiveKind", "archiveSchemaVersion", "complete", "cutoffAt", "exporterVersion",
        "inventory", "normalizationVersion", "previousManifestSha256", "privacy",
        "routeReferences", "shards", "summary", "terminalFreeze");
    private static final Set<String> PRIVACY_FIELDS = Set.of(
        "contains_credentials", "contains_original_vehicle_ids", "contains_plate_values",
        "contains_raw_responses", "contains_source_hmac_values");

    public ArchiveManifest {
        routeReferences = List.copyOf(routeReferences);
        shards = List.copyOf(shards);
        if (!Set.of("BASE", "LATE_DELTA", "TERMINAL_DELTA").contains(archiveKind)) {
            throw new MigrationException("ARCHIVE_KIND_INVALID");
        }
        if (("BASE".equals(archiveKind)) != (previousManifestSha256 == null)) {
            throw new MigrationException("ARCHIVE_PREVIOUS_MANIFEST_INVALID");
        }
        if (("TERMINAL_DELTA".equals(archiveKind)) != (terminalFreeze != null)) {
            throw new MigrationException("ARCHIVE_TERMINAL_FREEZE_INVALID");
        }
        if (previousManifestSha256 != null) {
            Sha256.requireDigest(previousManifestSha256, "ARCHIVE_PREVIOUS_MANIFEST_DIGEST_INVALID");
        }
        if (exporterVersion == null || exporterVersion.isBlank() || cutoffAt == null
            || !"normalization-v1.0.0-s3-backfill".equals(normalizationVersion)) {
            throw new MigrationException("ARCHIVE_MANIFEST_IDENTITY_INVALID");
        }
        if (terminalFreeze != null
            && (!terminalFreeze.finalInventorySha256().equals(inventory.sha256())
                || cutoffAt.isBefore(terminalFreeze.drainedAt()))) {
            throw new MigrationException("ARCHIVE_TERMINAL_INVENTORY_MISMATCH");
        }
        long batchCount = shards.stream().mapToLong(Shard::batchCount).sum();
        long observationCount = shards.stream().mapToLong(Shard::observationCount).sum();
        if (batchCount != summary.batchCount || observationCount != summary.observationCount) {
            throw new MigrationException("ARCHIVE_MANIFEST_SHARD_TOTAL_MISMATCH");
        }
        Map<String, Integer> nextOrdinal = new TreeMap<>();
        for (Shard shard : shards) {
            int expected = nextOrdinal.getOrDefault(shard.kstDate(), 0);
            if (shard.ordinal() != expected) {
                throw new MigrationException("ARCHIVE_SHARD_ORDINAL_INVALID");
            }
            nextOrdinal.put(shard.kstDate(), expected + 1);
        }
    }

    public byte[] canonicalBytes() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("archiveSchemaVersion", SCHEMA_VERSION);
        root.put("archiveKind", archiveKind);
        root.put("previousManifestSha256", previousManifestSha256);
        root.put("exporterVersion", exporterVersion);
        root.put("cutoffAt", cutoffAt.toString());
        root.put("inventory", inventory.toMap());
        root.put("normalizationVersion", normalizationVersion);
        root.put("complete", complete);
        root.put("terminalFreeze", terminalFreeze == null ? null : terminalFreeze.toMap());
        root.put("routeReferences", routeReferences.stream().map(RouteRoster::toMap).toList());
        root.put("shards", shards.stream().map(Shard::toMap).toList());
        root.put("summary", summary.toMap());
        root.put("privacy", privacy());
        return CanonicalJson.bytesOf(root);
    }

    public String sha256() {
        return Sha256.of(canonicalBytes());
    }

    public void writeTo(
        Path directory
    ) {
        SecureFiles.createPrivateDirectory(directory);
        Path manifest = directory.resolve(MANIFEST_FILE);
        SecureFiles.writeNew(manifest, canonicalBytes());
        SecureFiles.writeNew(
            directory.resolve(MANIFEST_DIGEST_FILE),
            (sha256() + "  " + MANIFEST_FILE + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    public static ArchiveManifest readVerified(
        Path directory
    ) {
        Path manifestPath = directory.resolve(MANIFEST_FILE);
        SecureFiles.requirePrivateRegularFile(manifestPath);
        SecureFiles.requirePrivateRegularFile(directory.resolve(MANIFEST_DIGEST_FILE));
        JsonNode root = CanonicalJson.readCanonical(manifestPath, "ARCHIVE_MANIFEST_JSON_INVALID");
        String expected = readDigestFile(directory.resolve(MANIFEST_DIGEST_FILE));
        String actual = Sha256.of(manifestPath);
        if (!expected.equals(actual)) {
            throw new MigrationException("ARCHIVE_MANIFEST_DIGEST_MISMATCH");
        }
        ArchiveManifest manifest = from(root);
        if (!actual.equals(manifest.sha256())) {
            throw new MigrationException("ARCHIVE_MANIFEST_ROUND_TRIP_MISMATCH");
        }
        return manifest;
    }

    private static ArchiveManifest from(
        JsonNode root
    ) {
        ArchiveJson.requireObjectWithFields(root, FIELDS, "ARCHIVE_MANIFEST_FIELDS_INVALID");
        if (!SCHEMA_VERSION.equals(ArchiveJson.text(root, "archiveSchemaVersion"))) {
            throw new MigrationException("ARCHIVE_MANIFEST_SCHEMA_INVALID");
        }
        requirePrivacy(ArchiveJson.object(root, "privacy"));
        List<RouteRoster> rosters = new ArrayList<>();
        for (JsonNode node : ArchiveJson.array(root, "routeReferences")) {
            rosters.add(RouteRoster.fromManifest(node));
        }
        List<Shard> shards = new ArrayList<>();
        for (JsonNode node : ArchiveJson.array(root, "shards")) {
            shards.add(Shard.from(node));
        }
        try {
            return new ArchiveManifest(
                ArchiveJson.text(root, "archiveKind"),
                ArchiveJson.nullableText(root, "previousManifestSha256"),
                ArchiveJson.text(root, "exporterVersion"),
                Instant.parse(ArchiveJson.text(root, "cutoffAt")),
                Inventory.from(ArchiveJson.object(root, "inventory")),
                ArchiveJson.text(root, "normalizationVersion"),
                ArchiveJson.bool(root, "complete"),
                TerminalFreeze.fromNullable(root.get("terminalFreeze")),
                rosters,
                shards,
                Summary.from(ArchiveJson.object(root, "summary")));
        } catch (DateTimeParseException e) {
            throw new MigrationException("ARCHIVE_MANIFEST_TIME_INVALID", e);
        }
    }

    public record TerminalFreeze(
        String scheduleIdentitySha256,
        String terminalReceiptSha256,
        String finalInventorySha256,
        String terminalPartitionInventorySha256,
        String immutableBaseInventorySha256,
        String sourceClosureSha256,
        String collectorCodeSha256Base64,
        Instant disabledAt,
        Instant drainedAt
    ) {

        private static final Set<String> FIELDS = Set.of(
            "collectorCodeSha256Base64", "disabledAt", "drainedAt", "finalInventorySha256",
            "immutableBaseInventorySha256", "scheduleIdentitySha256", "sourceClosureSha256",
            "terminalPartitionInventorySha256", "terminalReceiptSha256");

        public static final String EXPECTED_FINAL_INVENTORY_SHA256 =
            "ad7dca914792eb243008df549fef844e5a32d004a867d7c10c44b6c979b7fad8";
        public static final String EXPECTED_TERMINAL_PARTITION_INVENTORY_SHA256 =
            "f0decee3446e0e787532c9682bd3c6c627ccf9f10cdba9a992829345fdefa86e";
        public static final String EXPECTED_IMMUTABLE_BASE_INVENTORY_SHA256 =
            "db47305386c77fd6d28411ce09b5e1633a029027bc15d41c8201139fb9d535b9";
        public static final String EXPECTED_SOURCE_CLOSURE_SHA256 =
            "75fc9d3f27e73fe60dc63d2d6eea957acc3eab0fdc9c104f98624b3847605bc7";
        public static final String EXPECTED_COLLECTOR_CODE_SHA256_BASE64 =
            "EkdK2EVaQTdfth3ZbEKNJKP5CeLcru1k7MTbNJNOxjs=";
        public static final Instant EXPECTED_DISABLED_AT =
            Instant.parse("2026-09-02T13:18:57.240Z");

        public TerminalFreeze {
            Sha256.requireDigest(scheduleIdentitySha256, "TERMINAL_SCHEDULE_DIGEST_INVALID");
            Sha256.requireDigest(terminalReceiptSha256, "TERMINAL_RECEIPT_DIGEST_INVALID");
            Sha256.requireDigest(finalInventorySha256, "TERMINAL_INVENTORY_DIGEST_INVALID");
            Sha256.requireDigest(
                terminalPartitionInventorySha256, "TERMINAL_PARTITION_DIGEST_INVALID");
            Sha256.requireDigest(immutableBaseInventorySha256, "TERMINAL_BASE_DIGEST_INVALID");
            Sha256.requireDigest(sourceClosureSha256, "TERMINAL_SOURCE_CLOSURE_DIGEST_INVALID");
            if (!EXPECTED_FINAL_INVENTORY_SHA256.equals(finalInventorySha256)
                || !EXPECTED_TERMINAL_PARTITION_INVENTORY_SHA256.equals(
                    terminalPartitionInventorySha256)
                || !EXPECTED_IMMUTABLE_BASE_INVENTORY_SHA256.equals(immutableBaseInventorySha256)
                || !EXPECTED_SOURCE_CLOSURE_SHA256.equals(sourceClosureSha256)
                || !EXPECTED_COLLECTOR_CODE_SHA256_BASE64.equals(collectorCodeSha256Base64)
                || !EXPECTED_DISABLED_AT.equals(disabledAt)) {
                throw new MigrationException("TERMINAL_FREEZE_AUTHORITY_MISMATCH");
            }
            if (collectorCodeSha256Base64 == null || collectorCodeSha256Base64.isBlank()
                || disabledAt == null || drainedAt == null || drainedAt.isBefore(disabledAt)) {
                throw new MigrationException("TERMINAL_FREEZE_FIELDS_INVALID");
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("scheduleIdentitySha256", scheduleIdentitySha256);
            value.put("terminalReceiptSha256", terminalReceiptSha256);
            value.put("finalInventorySha256", finalInventorySha256);
            value.put("terminalPartitionInventorySha256", terminalPartitionInventorySha256);
            value.put("immutableBaseInventorySha256", immutableBaseInventorySha256);
            value.put("sourceClosureSha256", sourceClosureSha256);
            value.put("collectorCodeSha256Base64", collectorCodeSha256Base64);
            value.put("disabledAt", disabledAt.toString());
            value.put("drainedAt", drainedAt.toString());
            return value;
        }

        static TerminalFreeze fromNullable(JsonNode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            ArchiveJson.requireObjectWithFields(node, FIELDS, "TERMINAL_FREEZE_FIELDS_INVALID");
            try {
                return new TerminalFreeze(
                    ArchiveJson.text(node, "scheduleIdentitySha256"),
                    ArchiveJson.text(node, "terminalReceiptSha256"),
                    ArchiveJson.text(node, "finalInventorySha256"),
                    ArchiveJson.text(node, "terminalPartitionInventorySha256"),
                    ArchiveJson.text(node, "immutableBaseInventorySha256"),
                    ArchiveJson.text(node, "sourceClosureSha256"),
                    ArchiveJson.text(node, "collectorCodeSha256Base64"),
                    Instant.parse(ArchiveJson.text(node, "disabledAt")),
                    Instant.parse(ArchiveJson.text(node, "drainedAt")));
            } catch (DateTimeParseException e) {
                throw new MigrationException("TERMINAL_FREEZE_TIME_INVALID", e);
            }
        }
    }

    private static Map<String, Object> privacy() {
        Map<String, Object> privacy = new LinkedHashMap<>();
        privacy.put("contains_original_vehicle_ids", true);
        privacy.put("contains_plate_values", false);
        privacy.put("contains_source_hmac_values", false);
        privacy.put("contains_raw_responses", false);
        privacy.put("contains_credentials", false);
        return privacy;
    }

    private static void requirePrivacy(
        JsonNode privacy
    ) {
        ArchiveJson.requireObjectWithFields(privacy, PRIVACY_FIELDS, "ARCHIVE_PRIVACY_FIELDS_INVALID");
        if (!ArchiveJson.bool(privacy, "contains_original_vehicle_ids")
            || ArchiveJson.bool(privacy, "contains_plate_values")
            || ArchiveJson.bool(privacy, "contains_source_hmac_values")
            || ArchiveJson.bool(privacy, "contains_raw_responses")
            || ArchiveJson.bool(privacy, "contains_credentials")) {
            throw new MigrationException("ARCHIVE_PRIVACY_POLICY_INVALID");
        }
    }

    private static String readDigestFile(
        Path path
    ) {
        try {
            String content = Files.readString(path, StandardCharsets.US_ASCII);
            if (!content.matches("[0-9a-f]{64}  manifest\\.json\\n")) {
                throw new MigrationException("ARCHIVE_MANIFEST_DIGEST_FILE_INVALID");
            }
            return content.substring(0, 64);
        } catch (IOException e) {
            throw new MigrationException("ARCHIVE_MANIFEST_DIGEST_FILE_READ_FAILED", e);
        }
    }

    public record Inventory(
        String algorithm,
        String sha256,
        long recordObjects,
        long rawObjects,
        long kmsEncryptedObjects,
        List<String> kmsKeyDigests
    ) {

        private static final Set<String> FIELDS = Set.of(
            "algorithm", "kmsEncryptedObjects", "kmsKeyDigests", "rawObjects", "recordObjects", "sha256");

        public Inventory {
            kmsKeyDigests = List.copyOf(kmsKeyDigests);
            if (!"object-inventory-v1".equals(algorithm) || recordObjects < 0 || rawObjects < 0
                || kmsEncryptedObjects < 0 || kmsEncryptedObjects > recordObjects + rawObjects
                || kmsKeyDigests.stream().anyMatch(value -> !Sha256.isDigest(value))) {
                throw new MigrationException("ARCHIVE_INVENTORY_INVALID");
            }
            Sha256.requireDigest(sha256, "ARCHIVE_INVENTORY_DIGEST_INVALID");
        }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("algorithm", algorithm);
            value.put("sha256", sha256);
            value.put("recordObjects", recordObjects);
            value.put("rawObjects", rawObjects);
            value.put("kmsEncryptedObjects", kmsEncryptedObjects);
            value.put("kmsKeyDigests", kmsKeyDigests);
            return value;
        }

        static Inventory from(
            JsonNode node
        ) {
            ArchiveJson.requireObjectWithFields(node, FIELDS, "ARCHIVE_INVENTORY_FIELDS_INVALID");
            return new Inventory(
                ArchiveJson.text(node, "algorithm"),
                ArchiveJson.text(node, "sha256"),
                ArchiveJson.longValue(node, "recordObjects"),
                ArchiveJson.longValue(node, "rawObjects"),
                ArchiveJson.longValue(node, "kmsEncryptedObjects"),
                textList(node, "kmsKeyDigests"));
        }

        private static List<String> textList(
            JsonNode node,
            String field
        ) {
            List<String> values = new ArrayList<>();
            for (JsonNode value : ArchiveJson.array(node, field)) {
                if (!value.isString()) {
                    throw new MigrationException("ARCHIVE_INVENTORY_KMS_DIGEST_INVALID");
                }
                values.add(value.stringValue());
            }
            return values;
        }
    }

    public record Shard(
        int ordinal,
        String kstDate,
        String sha256,
        String uncompressedSha256,
        String file,
        long compressedBytes,
        long uncompressedBytes,
        int batchCount,
        long observationCount,
        Instant collectedFrom,
        Instant collectedThrough
    ) {

        private static final Set<String> FIELDS = Set.of(
            "batchCount", "collectedFrom", "collectedThrough", "compressedBytes",
            "file", "kstDate", "observationCount", "ordinal", "sha256",
            "uncompressedBytes", "uncompressedSha256");

        public Shard {
            Sha256.requireDigest(sha256, "ARCHIVE_SHARD_DIGEST_INVALID");
            Sha256.requireDigest(uncompressedSha256, "ARCHIVE_SHARD_UNCOMPRESSED_DIGEST_INVALID");
            if (ordinal < 0 || kstDate == null || !kstDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                || compressedBytes < 1 || compressedBytes > 16L * 1024 * 1024
                || uncompressedBytes < 1 || uncompressedBytes > 64L * 1024 * 1024
                || batchCount < 1 || observationCount < 0
                || collectedFrom == null || collectedThrough == null
                || collectedThrough.isBefore(collectedFrom)
                || !file.equals("shards/dt=" + kstDate + "_" + "%03d".formatted(ordinal)
                    + "_sha256-" + sha256 + ".jsonl.zst")) {
                throw new MigrationException("ARCHIVE_SHARD_INVALID");
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("ordinal", ordinal);
            value.put("kstDate", kstDate);
            value.put("sha256", sha256);
            value.put("uncompressedSha256", uncompressedSha256);
            value.put("file", file);
            value.put("compressedBytes", compressedBytes);
            value.put("uncompressedBytes", uncompressedBytes);
            value.put("batchCount", batchCount);
            value.put("observationCount", observationCount);
            value.put("collectedFrom", collectedFrom.toString());
            value.put("collectedThrough", collectedThrough.toString());
            return value;
        }

        static Shard from(
            JsonNode node
        ) {
            ArchiveJson.requireObjectWithFields(node, FIELDS, "ARCHIVE_SHARD_FIELDS_INVALID");
            try {
                return new Shard(
                    ArchiveJson.integer(node, "ordinal"),
                    ArchiveJson.text(node, "kstDate"),
                    ArchiveJson.text(node, "sha256"),
                    ArchiveJson.text(node, "uncompressedSha256"),
                    ArchiveJson.text(node, "file"),
                    ArchiveJson.longValue(node, "compressedBytes"),
                    ArchiveJson.longValue(node, "uncompressedBytes"),
                    ArchiveJson.integer(node, "batchCount"),
                    ArchiveJson.longValue(node, "observationCount"),
                    Instant.parse(ArchiveJson.text(node, "collectedFrom")),
                    Instant.parse(ArchiveJson.text(node, "collectedThrough")));
            } catch (DateTimeParseException e) {
                throw new MigrationException("ARCHIVE_SHARD_TIME_INVALID", e);
            }
        }
    }

    public record Summary(
        long batchCount,
        long observationCount,
        long excludedObservationCount,
        long quarantinedBatchCount,
        long quarantinedObservationCount,
        long overlapBatchCount,
        long overlapObservationCount,
        Instant collectedFrom,
        Instant collectedThrough,
        Map<String, Long> batchesPerRoute,
        Map<String, Long> observationsPerRoute,
        Map<String, Long> batchesPerKstDate,
        Map<String, Long> observationsPerKstDate,
        Map<String, Long> rejectsByCode
    ) {

        private static final Set<String> FIELDS = Set.of(
            "batchCount", "batchesPerKstDate", "batchesPerRoute", "collectedFrom",
            "collectedThrough", "excludedObservationCount", "observationCount",
            "overlapBatchCount", "overlapObservationCount", "quarantinedBatchCount",
            "quarantinedObservationCount",
            "observationsPerKstDate", "observationsPerRoute", "rejectsByCode");

        public Summary {
            batchesPerRoute = Map.copyOf(new TreeMap<>(batchesPerRoute));
            observationsPerRoute = Map.copyOf(new TreeMap<>(observationsPerRoute));
            batchesPerKstDate = Map.copyOf(new TreeMap<>(batchesPerKstDate));
            observationsPerKstDate = Map.copyOf(new TreeMap<>(observationsPerKstDate));
            rejectsByCode = Map.copyOf(new TreeMap<>(rejectsByCode));
            if (batchCount < 0 || observationCount < 0 || excludedObservationCount < 0
                || quarantinedBatchCount < 0 || quarantinedObservationCount < 0
                || overlapBatchCount < 0 || overlapObservationCount < 0
                || (batchCount > 0 && (collectedFrom == null || collectedThrough == null))
                || (collectedFrom != null && collectedThrough != null && collectedThrough.isBefore(collectedFrom))) {
                throw new MigrationException("ARCHIVE_SUMMARY_INVALID");
            }
            requireSum(batchesPerRoute, batchCount, "ARCHIVE_ROUTE_BATCH_SUM_MISMATCH");
            requireSum(observationsPerRoute, observationCount, "ARCHIVE_ROUTE_OBSERVATION_SUM_MISMATCH");
            requireSum(batchesPerKstDate, batchCount, "ARCHIVE_DATE_BATCH_SUM_MISMATCH");
            requireSum(observationsPerKstDate, observationCount, "ARCHIVE_DATE_OBSERVATION_SUM_MISMATCH");
        }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("batchCount", batchCount);
            value.put("observationCount", observationCount);
            value.put("excludedObservationCount", excludedObservationCount);
            value.put("quarantinedBatchCount", quarantinedBatchCount);
            value.put("quarantinedObservationCount", quarantinedObservationCount);
            value.put("overlapBatchCount", overlapBatchCount);
            value.put("overlapObservationCount", overlapObservationCount);
            value.put("collectedFrom", collectedFrom == null ? null : collectedFrom.toString());
            value.put("collectedThrough", collectedThrough == null ? null : collectedThrough.toString());
            value.put("batchesPerRoute", batchesPerRoute);
            value.put("observationsPerRoute", observationsPerRoute);
            value.put("batchesPerKstDate", batchesPerKstDate);
            value.put("observationsPerKstDate", observationsPerKstDate);
            value.put("rejectsByCode", rejectsByCode);
            return value;
        }

        static Summary from(
            JsonNode node
        ) {
            ArchiveJson.requireObjectWithFields(node, FIELDS, "ARCHIVE_SUMMARY_FIELDS_INVALID");
            try {
                return new Summary(
                    ArchiveJson.longValue(node, "batchCount"),
                    ArchiveJson.longValue(node, "observationCount"),
                    ArchiveJson.longValue(node, "excludedObservationCount"),
                    ArchiveJson.longValue(node, "quarantinedBatchCount"),
                    ArchiveJson.longValue(node, "quarantinedObservationCount"),
                    ArchiveJson.longValue(node, "overlapBatchCount"),
                    ArchiveJson.longValue(node, "overlapObservationCount"),
                    nullableInstant(node, "collectedFrom"),
                    nullableInstant(node, "collectedThrough"),
                    longMap(node, "batchesPerRoute"),
                    longMap(node, "observationsPerRoute"),
                    longMap(node, "batchesPerKstDate"),
                    longMap(node, "observationsPerKstDate"),
                    longMap(node, "rejectsByCode"));
            } catch (DateTimeParseException e) {
                throw new MigrationException("ARCHIVE_SUMMARY_TIME_INVALID", e);
            }
        }

        private static Instant nullableInstant(
            JsonNode node,
            String field
        ) {
            String value = ArchiveJson.nullableText(node, field);
            return value == null ? null : Instant.parse(value);
        }

        private static Map<String, Long> longMap(
            JsonNode node,
            String field
        ) {
            JsonNode object = ArchiveJson.object(node, field);
            Map<String, Long> values = new TreeMap<>();
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                JsonNode value = entry.getValue();
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
                    throw new MigrationException("ARCHIVE_SUMMARY_MAP_INVALID");
                }
                values.put(entry.getKey(), value.longValue());
            }
            return values;
        }

        private static void requireSum(
            Map<String, Long> values,
            long expected,
            String code
        ) {
            long actual = values.values().stream().mapToLong(Long::longValue).sum();
            if (actual != expected) {
                throw new MigrationException(code);
            }
        }
    }
}
