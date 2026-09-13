package com.gustler.backend.migration.archive;

import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ArchiveVerifier {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public Verification verify(
        Path directory
    ) {
        SecureFiles.requirePrivateDirectory(directory);
        SecureFiles.requirePrivateDirectory(directory.resolve("shards"));
        ArchiveManifest manifest = ArchiveManifest.readVerified(directory);
        Accumulator accumulator = new Accumulator(manifest);
        ArchiveReader.forEachRecord(directory, manifest, accumulator::accept);
        accumulator.requireManifestMatch();
        return new Verification(
            manifest,
            manifest.sha256(),
            accumulator.batchCount,
            accumulator.observationCount,
            accumulator.excludedCount,
            accumulator.collectedFrom,
            accumulator.collectedThrough);
    }

    private static final class Accumulator {

        private final ArchiveManifest manifest;
        private final Set<java.util.UUID> recordIds = new HashSet<>();
        private final Set<String> semanticDigests = new HashSet<>();
        private final Map<String, Long> batchesPerRoute = new TreeMap<>();
        private final Map<String, Long> observationsPerRoute = new TreeMap<>();
        private final Map<String, Long> batchesPerDate = new TreeMap<>();
        private final Map<String, Long> observationsPerDate = new TreeMap<>();
        private long batchCount;
        private long observationCount;
        private long excludedCount;
        private Instant collectedFrom;
        private Instant collectedThrough;

        private Accumulator(
            ArchiveManifest manifest
        ) {
            this.manifest = manifest;
        }

        private void accept(
            ArchiveManifest.Shard shard,
            ArchiveReader.IndexedRecord indexed
        ) {
            ArchiveRecord record = indexed.record();
            if (!recordIds.add(record.batch().sourceRecordId())) {
                throw new MigrationException("ARCHIVE_DUPLICATE_SOURCE_RECORD_ID");
            }
            if (!semanticDigests.add(record.batch().semanticBatchDigest())) {
                throw new MigrationException("ARCHIVE_DUPLICATE_SEMANTIC_BATCH_DIGEST");
            }
            if (record.batch().responseReceivedAt().isAfter(manifest.cutoffAt())) {
                throw new MigrationException("ARCHIVE_RECORD_AFTER_CUTOFF");
            }
            RouteRoster roster = RouteRoster.forObservation(
                manifest.routeReferences(), record.batch().routeName(), record.batch().responseReceivedAt());
            if (!roster.sourceRouteId().equals(record.batch().sourceRouteId())) {
                throw new MigrationException("ARCHIVE_RECORD_ROUTE_REFERENCE_MISMATCH");
            }
            for (NormalizedObservation observation : record.observations()) {
                roster.requireStation(observation.stopOrder(), observation.stopId());
            }
            batchCount++;
            observationCount += record.observations().size();
            excludedCount += record.batch().excludedRows();
            collectedFrom = minimum(collectedFrom, record.batch().responseReceivedAt());
            collectedThrough = maximum(collectedThrough, record.batch().responseReceivedAt());
            String route = record.batch().routeName();
            String date = record.batch().responseReceivedAt().atZone(KST).toLocalDate().toString();
            increment(batchesPerRoute, route, 1);
            increment(observationsPerRoute, route, record.observations().size());
            increment(batchesPerDate, date, 1);
            increment(observationsPerDate, date, record.observations().size());
        }

        private void requireManifestMatch() {
            ArchiveManifest.Summary expected = manifest.summary();
            if (expected.batchCount() != batchCount
                || expected.observationCount() != observationCount
                || expected.excludedObservationCount() != excludedCount
                || !equals(expected.collectedFrom(), collectedFrom)
                || !equals(expected.collectedThrough(), collectedThrough)
                || !expected.batchesPerRoute().equals(batchesPerRoute)
                || !expected.observationsPerRoute().equals(observationsPerRoute)
                || !expected.batchesPerKstDate().equals(batchesPerDate)
                || !expected.observationsPerKstDate().equals(observationsPerDate)) {
                throw new MigrationException("ARCHIVE_RECONCILIATION_MISMATCH");
            }
        }

        private static void increment(
            Map<String, Long> values,
            String key,
            long increment
        ) {
            values.merge(key, increment, Long::sum);
        }

        private static Instant minimum(
            Instant first,
            Instant second
        ) {
            return first == null || second.isBefore(first) ? second : first;
        }

        private static Instant maximum(
            Instant first,
            Instant second
        ) {
            return first == null || second.isAfter(first) ? second : first;
        }

        private static boolean equals(
            Object first,
            Object second
        ) {
            return first == null ? second == null : first.equals(second);
        }
    }

    public record Verification(
        ArchiveManifest manifest,
        String manifestSha256,
        long batchCount,
        long observationCount,
        long excludedObservationCount,
        Instant collectedFrom,
        Instant collectedThrough
    ) {
    }
}
