package com.gustler.backend.migration.source;

import com.gustler.backend.migration.Configuration;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.archive.ArchiveRecord;
import java.time.Instant;
import java.util.Map;

record SourceAcceptancePolicy(
    Map<String, Instant> targetAuthorityFrom,
    long expectedAcceptedBatches,
    long expectedAcceptedObservations,
    long expectedQuarantinedBatches,
    long expectedQuarantinedObservations,
    long expectedOverlapBatches,
    long expectedOverlapObservations
) {

    SourceAcceptancePolicy {
        targetAuthorityFrom = Map.copyOf(targetAuthorityFrom);
        if (!targetAuthorityFrom.keySet().equals(java.util.Set.of("1650", "3330"))
            || expectedAcceptedBatches < 0 || expectedAcceptedObservations < 0
            || expectedQuarantinedBatches < 0 || expectedQuarantinedObservations < 0
            || expectedOverlapBatches < 0 || expectedOverlapObservations < 0) {
            throw new MigrationException("SOURCE_ACCEPTANCE_POLICY_INVALID");
        }
    }

    static SourceAcceptancePolicy load(Configuration configuration) {
        return new SourceAcceptancePolicy(
            Map.of(
                "3330", configuration.requiredInstant("source.target-authority-from.3330"),
                "1650", configuration.requiredInstant("source.target-authority-from.1650")),
            configuration.longValue("source.expected-accepted-batches", 0, 0),
            configuration.longValue("source.expected-accepted-observations", 0, 0),
            configuration.longValue("source.expected-quarantined-batches", 0, 0),
            configuration.longValue("source.expected-quarantined-observations", 0, 0),
            configuration.longValue("source.expected-overlap-batches", 0, 0),
            configuration.longValue("source.expected-overlap-observations", 0, 0));
    }

    boolean isOverlap(ArchiveRecord record) {
        Instant seam = targetAuthorityFrom.get(record.batch().routeName());
        if (seam == null) {
            throw new MigrationException("SOURCE_ROUTE_AUTHORITY_MISSING");
        }
        return !record.batch().responseReceivedAt().isBefore(seam);
    }

    boolean matches(SourceArchiveExporter.Accumulator totals) {
        return totals.batchCount == expectedAcceptedBatches
            && totals.observationCount == expectedAcceptedObservations
            && totals.quarantinedBatches == expectedQuarantinedBatches
            && totals.quarantinedObservations == expectedQuarantinedObservations
            && totals.overlapBatches == expectedOverlapBatches
            && totals.overlapObservations == expectedOverlapObservations;
    }
}
