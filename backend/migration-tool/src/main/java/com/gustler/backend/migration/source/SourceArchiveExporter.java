package com.gustler.backend.migration.source;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.Configuration;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.Sha256;
import com.gustler.backend.migration.archive.ArchiveManifest;
import com.gustler.backend.migration.archive.ArchiveRecord;
import com.gustler.backend.migration.archive.ArchiveVerifier;
import com.gustler.backend.migration.archive.RouteRoster;
import com.gustler.backend.migration.archive.ShardWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

public final class SourceArchiveExporter {

    public static final String EXPORTER_VERSION = "s3-rds-migration-v1";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<String> FATAL_CODES = Set.of(
        "SOURCE_S3_GET_OR_KMS_DECRYPT_DENIED",
        "SOURCE_S3_GET_FAILED",
        "SOURCE_OBJECT_CHANGED_AFTER_INVENTORY");

    private final SourceObjectStore store;

    public SourceArchiveExporter(
        SourceObjectStore store
    ) {
        this.store = store;
    }

    public BuildResult build(
        SourceInventory inventory,
        Path protocolPath,
        Path outputDirectory,
        int maxBatchesPerShard,
        String previousManifestSha256,
        SourceAcceptancePolicy acceptancePolicy,
        ArchiveManifest.TerminalFreeze terminalFreeze
    ) {
        requireFreshOutput(outputDirectory);
        if (!inventory.sourceAccountId().equals(store.callerAccountId())) {
            throw new MigrationException("SOURCE_AWS_ACCOUNT_MISMATCH");
        }
        List<RouteRoster> rosters = loadRosters(protocolPath);
        SourceRecordValidator validator = new SourceRecordValidator(rosters);
        Map<String, SourceObjectStore.ObjectInfo> allByKey = byKey(inventory.entries());
        List<SourceObjectStore.ObjectInfo> selectedRecords = inventory.selectedEntries().stream()
            .filter(entry -> entry.kind() == SourceObjectStore.Kind.RECORD)
            .sorted(java.util.Comparator.comparing(
                entry -> partitionDate(entry.key()) + "\n" + entry.key()))
            .toList();
        Set<String> selectedRawKeys = new HashSet<>();
        inventory.selectedEntries().stream()
            .filter(entry -> entry.kind() == SourceObjectStore.Kind.RAW)
            .map(SourceObjectStore.ObjectInfo::key)
            .forEach(selectedRawKeys::add);

        Accumulator totals = new Accumulator();
        Map<String, String> vehicleToHmac = new HashMap<>();
        Map<String, String> hmacToVehicle = new HashMap<>();
        Set<java.util.UUID> recordIds = new HashSet<>();
        Set<String> semanticDigests = new HashSet<>();
        Set<String> usedRawKeys = new HashSet<>();
        Set<String> kmsKeyDigests = new HashSet<>();
        long[] kmsObjects = {0};
        List<ArchiveRecord> dateRecords = new ArrayList<>();
        String currentPartitionDate = null;

        SecureFiles.createPrivateDirectory(outputDirectory);
        try (ShardWriter shards = new ShardWriter(outputDirectory, maxBatchesPerShard)) {
            for (SourceObjectStore.ObjectInfo recordInfo : selectedRecords) {
                String partitionDate = partitionDate(recordInfo.key());
                if (currentPartitionDate != null && !currentPartitionDate.equals(partitionDate)) {
                    flushDate(dateRecords, shards, totals);
                }
                currentPartitionDate = partitionDate;
                try {
                    SourceObjectStore.LoadedObject record = store.get(inventory.bucket(), recordInfo);
                    rememberEncryption(record, kmsObjects, kmsKeyDigests);
                    String rawKey = SourceRecordValidator.expectedRawKeyOf(recordInfo.key());
                    SourceObjectStore.ObjectInfo rawInfo = allByKey.get(rawKey);
                    SourceObjectStore.LoadedObject raw = rawInfo == null ? null : store.get(inventory.bucket(), rawInfo);
                    if (raw != null) {
                        rememberEncryption(raw, kmsObjects, kmsKeyDigests);
                    }
                    SourceRecordValidator.Validated validated = validator.validate(
                        record, raw, inventory.cutoffAt());
                    requireIdentityConsistency(validated.identities(), vehicleToHmac, hmacToVehicle);
                    ArchiveRecord archiveRecord = validated.record();
                    if (!recordIds.add(archiveRecord.batch().sourceRecordId())) {
                        throw new MigrationException("SOURCE_DUPLICATE_RECORD_IDENTIFIER");
                    }
                    if (!semanticDigests.add(archiveRecord.batch().semanticBatchDigest())) {
                        throw new MigrationException("SOURCE_DUPLICATE_SEMANTIC_BATCH");
                    }
                    if (validated.referencedRawKey() != null) {
                        if (!usedRawKeys.add(validated.referencedRawKey())) {
                            throw new MigrationException("SOURCE_RAW_BIJECTION_VIOLATION");
                        }
                    }
                    if (acceptancePolicy.isOverlap(archiveRecord)) {
                        totals.overlap(archiveRecord.batch().providerRows());
                        continue;
                    }
                    dateRecords.add(archiveRecord);
                } catch (MigrationException e) {
                    if (FATAL_CODES.contains(e.code())) {
                        throw e;
                    }
                    long rejectedRows = providerRowsForReceipt(recordInfo, inventory.bucket());
                    if ("ROUTE_ROSTER_STATION_MISMATCH".equals(e.code())) {
                        usedRawKeys.add(SourceRecordValidator.expectedRawKeyOf(recordInfo.key()));
                        totals.quarantine(e.code(), rejectedRows);
                    } else {
                        totals.reject(e.code());
                    }
                }
            }
            flushDate(dateRecords, shards, totals);
            for (String rawKey : selectedRawKeys) {
                if (!usedRawKeys.contains(rawKey)) {
                totals.reject("SOURCE_UNREFERENCED_RAW_OBJECT");
                }
            }
            List<ArchiveManifest.Shard> completedShards = shards.shards();
            ArchiveManifest manifest = new ArchiveManifest(
                archiveKind(inventory, terminalFreeze),
                requiredPreviousManifest(inventory, previousManifestSha256),
                EXPORTER_VERSION,
                inventory.cutoffAt(),
                new ArchiveManifest.Inventory(
                    "object-inventory-v1",
                    inventory.inventorySha256(),
                    selectedRecords.size(),
                    usedRawKeys.size(),
                    kmsObjects[0],
                    kmsKeyDigests.stream().sorted().toList()),
                "normalization-v1.0.0-s3-backfill",
                totals.rejects.keySet().stream().allMatch("ROUTE_ROSTER_STATION_MISMATCH"::equals)
                    && usedRawKeys.size() == selectedRawKeys.size()
                    && acceptancePolicy.matches(totals),
                terminalFreeze,
                rosters,
                completedShards,
                totals.summary());
            manifest.writeTo(outputDirectory);
            new ArchiveVerifier().verify(outputDirectory);
            vehicleToHmac.clear();
            hmacToVehicle.clear();
            return new BuildResult(
                manifest.sha256(), totals.batchCount, totals.observationCount,
                Map.copyOf(totals.rejects), manifest.complete());
        }
    }

    public static BuildResult build(
        Configuration configuration,
        SourceObjectStore store,
        Path inventoryPath
    ) {
        SourceInventory inventory = SourceInventory.read(inventoryPath);
        String terminalReceiptPath = configuration.optional("source.terminal-freeze-receipt");
        ArchiveManifest.TerminalFreeze terminalFreeze = terminalReceiptPath == null ? null
            : TerminalFreezeReceipt.read(Path.of(terminalReceiptPath), inventory);
        return new SourceArchiveExporter(store).build(
            inventory,
            configuration.requiredPath("route-reference.file"),
            configuration.requiredPath("archive.output-directory"),
            configuration.integer("archive.max-batches-per-shard", 10_000, 1, 10_000),
            configuration.optional("archive.previous-manifest-sha256"),
            SourceAcceptancePolicy.load(configuration),
            terminalFreeze);
    }

    private static List<RouteRoster> loadRosters(
        Path protocolPath
    ) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(protocolPath);
        } catch (IOException e) {
            throw new MigrationException("ROUTE_REFERENCE_READ_FAILED", e);
        }
        JsonNode protocol = CanonicalJson.parse(bytes, "ROUTE_REFERENCE_JSON_INVALID");
        return RouteRoster.loadProtocol(protocol);
    }

    private static void requireFreshOutput(
        Path outputDirectory
    ) {
        if (Files.exists(outputDirectory) || Files.isSymbolicLink(outputDirectory)) {
            throw new MigrationException("ARCHIVE_OUTPUT_ALREADY_EXISTS");
        }
    }

    private static Map<String, SourceObjectStore.ObjectInfo> byKey(
        List<SourceObjectStore.ObjectInfo> entries
    ) {
        Map<String, SourceObjectStore.ObjectInfo> result = new HashMap<>();
        for (SourceObjectStore.ObjectInfo entry : entries) {
            if (result.put(entry.key(), entry) != null) {
                throw new MigrationException("SOURCE_INVENTORY_DUPLICATE_KEY");
            }
        }
        return result;
    }

    private static void rememberEncryption(
        SourceObjectStore.LoadedObject object,
        long[] kmsObjects,
        Set<String> kmsKeyDigests
    ) {
        if (!object.kmsEncrypted()) {
            return;
        }
        kmsObjects[0]++;
        if (object.kmsKeyDigest() != null) {
            kmsKeyDigests.add(object.kmsKeyDigest());
        }
    }

    private static void flushDate(
        List<ArchiveRecord> records,
        ShardWriter shards,
        Accumulator totals
    ) {
        records.sort(java.util.Comparator.comparing(record -> record.batch().semanticBatchDigest()));
        for (ArchiveRecord record : records) {
            shards.append(record);
            totals.accept(record);
        }
        records.clear();
    }

    private static String partitionDate(
        String key
    ) {
        int start = key.indexOf("/dt=");
        if (start < 0 || key.length() < start + 14) {
            throw new MigrationException("SOURCE_RECORD_PARTITION_INVALID");
        }
        return key.substring(start + 4, start + 14);
    }

    private static void requireIdentityConsistency(
        List<SourceRecordValidator.IdentityPair> identities,
        Map<String, String> vehicleToHmac,
        Map<String, String> hmacToVehicle
    ) {
        for (SourceRecordValidator.IdentityPair identity : identities) {
            if (identity.vehicleId() == null) {
                continue;
            }
            String priorHmac = vehicleToHmac.get(identity.vehicleId());
            String priorVehicle = hmacToVehicle.get(identity.vehicleHmac());
            if ((priorHmac != null && !priorHmac.equals(identity.vehicleHmac()))
                || (priorVehicle != null && !priorVehicle.equals(identity.vehicleId()))) {
                throw new MigrationException("SOURCE_VEHICLE_IDENTITY_CONFLICT");
            }
        }
        for (SourceRecordValidator.IdentityPair identity : identities) {
            if (identity.vehicleId() == null) {
                continue;
            }
            vehicleToHmac.putIfAbsent(identity.vehicleId(), identity.vehicleHmac());
            hmacToVehicle.putIfAbsent(identity.vehicleHmac(), identity.vehicleId());
        }
    }

    private static String requiredPreviousManifest(
        SourceInventory inventory,
        String configured
    ) {
        if (inventory.previousInventorySha256() == null) {
            if (configured != null) {
                throw new MigrationException("SOURCE_BASE_ARCHIVE_PREVIOUS_MANIFEST_FORBIDDEN");
            }
            return null;
        }
        Sha256.requireDigest(configured, "SOURCE_CATCHUP_PREVIOUS_MANIFEST_REQUIRED");
        return configured;
    }

    private static String archiveKind(
        SourceInventory inventory,
        ArchiveManifest.TerminalFreeze terminalFreeze
    ) {
        if (terminalFreeze != null) {
            if (inventory.previousInventorySha256() == null) {
                throw new MigrationException("TERMINAL_FREEZE_REQUIRES_PREVIOUS_INVENTORY");
            }
            return "TERMINAL_DELTA";
        }
        return inventory.previousInventorySha256() == null ? "BASE" : "LATE_DELTA";
    }

    private long providerRowsForReceipt(
        SourceObjectStore.ObjectInfo recordInfo,
        String bucket
    ) {
        try {
            SourceObjectStore.LoadedObject loaded = store.get(bucket, recordInfo);
            JsonNode root = CanonicalJson.parse(loaded.body(), "SOURCE_RECORD_JSON_INVALID");
            JsonNode count = root.path("classification").get("vehicle_count");
            if (count == null || !count.isIntegralNumber() || !count.canConvertToLong()) {
                return 0;
            }
            return Math.max(count.longValue(), 0);
        } catch (MigrationException ignored) {
            return 0;
        }
    }

    public record BuildResult(
        String manifestSha256,
        long acceptedBatches,
        long acceptedObservations,
        Map<String, Long> rejectsByCode,
        boolean complete
    ) {
    }

    static final class Accumulator {

        private final Map<String, Long> batchesPerRoute = new TreeMap<>();
        private final Map<String, Long> observationsPerRoute = new TreeMap<>();
        private final Map<String, Long> batchesPerDate = new TreeMap<>();
        private final Map<String, Long> observationsPerDate = new TreeMap<>();
        private final Map<String, Long> rejects = new TreeMap<>();
        long batchCount;
        long observationCount;
        private long excludedCount;
        long quarantinedBatches;
        long quarantinedObservations;
        long overlapBatches;
        long overlapObservations;
        private Instant collectedFrom;
        private Instant collectedThrough;

        private void accept(
            ArchiveRecord record
        ) {
            batchCount++;
            observationCount += record.observations().size();
            excludedCount += record.batch().excludedRows();
            Instant at = record.batch().responseReceivedAt();
            collectedFrom = collectedFrom == null || at.isBefore(collectedFrom) ? at : collectedFrom;
            collectedThrough = collectedThrough == null || at.isAfter(collectedThrough) ? at : collectedThrough;
            String route = record.batch().routeName();
            String date = at.atZone(KST).toLocalDate().toString();
            batchesPerRoute.merge(route, 1L, Long::sum);
            observationsPerRoute.merge(route, (long) record.observations().size(), Long::sum);
            batchesPerDate.merge(date, 1L, Long::sum);
            observationsPerDate.merge(date, (long) record.observations().size(), Long::sum);
        }

        private void reject(
            String code
        ) {
            rejects.merge(code, 1L, Long::sum);
        }

        private void quarantine(String code, long observations) {
            rejects.merge(code, 1L, Long::sum);
            quarantinedBatches++;
            quarantinedObservations += observations;
        }

        private void overlap(long observations) {
            overlapBatches++;
            overlapObservations += observations;
        }

        private ArchiveManifest.Summary summary() {
            return new ArchiveManifest.Summary(
                batchCount,
                observationCount,
                excludedCount,
                quarantinedBatches,
                quarantinedObservations,
                overlapBatches,
                overlapObservations,
                collectedFrom,
                collectedThrough,
                batchesPerRoute,
                observationsPerRoute,
                batchesPerDate,
                observationsPerDate,
                rejects);
        }
    }
}
