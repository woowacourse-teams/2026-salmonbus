package com.gustler.backend.migration.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.Configuration;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.archive.ArchiveRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceAcceptancePolicyTest {

    private static final Instant SEAM_3330 = Instant.parse("2026-09-02T10:27:51.330754Z");
    private static final Instant SEAM_1650 = Instant.parse("2026-09-02T12:49:33.041299Z");
    private static final long BASE_ACCEPTED_BATCHES = 142_129;
    private static final long BASE_ACCEPTED_OBSERVATIONS = 2_324_399;
    private static final long BASE_QUARANTINED_BATCHES = 27;
    private static final long BASE_QUARANTINED_OBSERVATIONS = 545;

    @Test
    void acceptsOnlyTheExactApprovedQuarantineAndAcceptanceCounts() {
        SourceAcceptancePolicy policy = basePolicy();

        assertThat(policy.matches(totals(
            BASE_ACCEPTED_BATCHES, BASE_ACCEPTED_OBSERVATIONS,
            BASE_QUARANTINED_BATCHES, BASE_QUARANTINED_OBSERVATIONS, 0, 0)))
            .isTrue();
        assertThat(policy.matches(totals(
            BASE_ACCEPTED_BATCHES, BASE_ACCEPTED_OBSERVATIONS, 26, BASE_QUARANTINED_OBSERVATIONS, 0, 0)))
            .isFalse();
        assertThat(policy.matches(totals(
            BASE_ACCEPTED_BATCHES, BASE_ACCEPTED_OBSERVATIONS, 28, BASE_QUARANTINED_OBSERVATIONS, 0, 0)))
            .isFalse();
        assertThat(policy.matches(totals(
            BASE_ACCEPTED_BATCHES, BASE_ACCEPTED_OBSERVATIONS, BASE_QUARANTINED_BATCHES, 544, 0, 0)))
            .isFalse();
        assertThat(policy.matches(totals(
            BASE_ACCEPTED_BATCHES - 1, BASE_ACCEPTED_OBSERVATIONS,
            BASE_QUARANTINED_BATCHES, BASE_QUARANTINED_OBSERVATIONS, 0, 0)))
            .isFalse();
        assertThat(policy.matches(totals(
            BASE_ACCEPTED_BATCHES, BASE_ACCEPTED_OBSERVATIONS + 1,
            BASE_QUARANTINED_BATCHES, BASE_QUARANTINED_OBSERVATIONS, 0, 0)))
            .isFalse();
        assertThat(policy.matches(totals(
            BASE_ACCEPTED_BATCHES, BASE_ACCEPTED_OBSERVATIONS,
            BASE_QUARANTINED_BATCHES, BASE_QUARANTINED_OBSERVATIONS, 1, 0)))
            .isFalse();
        assertThat(policy.matches(totals(
            BASE_ACCEPTED_BATCHES, BASE_ACCEPTED_OBSERVATIONS,
            BASE_QUARANTINED_BATCHES, BASE_QUARANTINED_OBSERVATIONS, 0, 1)))
            .isFalse();
    }

    @Test
    void treatsTheAuthoritySeamItselfAsLiveOverlapAndRefusesAnUnknownRoute() {
        SourceAcceptancePolicy policy = basePolicy();

        assertThat(policy.isOverlap(record("3330", SEAM_3330))).isTrue();
        assertThat(policy.isOverlap(record("3330", SEAM_3330.minusNanos(1000)))).isFalse();
        assertThat(policy.isOverlap(record("3330", SEAM_1650))).isTrue();
        assertThat(policy.isOverlap(record("1650", SEAM_1650))).isTrue();
        assertThat(policy.isOverlap(record("1650", SEAM_1650.minusNanos(1000)))).isFalse();
        assertThat(policy.isOverlap(record("1650", SEAM_3330))).isFalse();
    }

    @Test
    void rejectsPolicyWithoutBothRoutesOrWithNegativeExpectation() {
        assertThatThrownBy(() -> new SourceAcceptancePolicy(
            Map.of("3330", SEAM_3330), 0, 0, 0, 0, 0, 0))
            .isInstanceOf(MigrationException.class)
            .hasMessage("SOURCE_ACCEPTANCE_POLICY_INVALID");
        assertThatThrownBy(() -> new SourceAcceptancePolicy(
            Map.of("1650", SEAM_1650, "3330", SEAM_3330, "9999", SEAM_3330), 0, 0, 0, 0, 0, 0))
            .isInstanceOf(MigrationException.class)
            .hasMessage("SOURCE_ACCEPTANCE_POLICY_INVALID");
        assertThatThrownBy(() -> new SourceAcceptancePolicy(
            Map.of("1650", SEAM_1650, "3330", SEAM_3330), 0, 0, -1, 0, 0, 0))
            .isInstanceOf(MigrationException.class)
            .hasMessage("SOURCE_ACCEPTANCE_POLICY_INVALID");
    }

    @Test
    void loadsTheApprovedCountsAndSeamsFromConfiguration(
        @TempDir Path directory
    ) throws Exception {
        Path file = directory.resolve("source-export.properties");
        Files.write(file, ("""
            source.target-authority-from.3330=2026-09-02T10:27:51.330754Z
            source.target-authority-from.1650=2026-09-02T12:49:33.041299Z
            source.expected-accepted-batches=142129
            source.expected-accepted-observations=2324399
            source.expected-quarantined-batches=27
            source.expected-quarantined-observations=545
            source.expected-overlap-batches=0
            source.expected-overlap-observations=0
            """).getBytes(StandardCharsets.UTF_8));

        SourceAcceptancePolicy policy = SourceAcceptancePolicy.load(Configuration.load(file));

        assertThat(policy).isEqualTo(basePolicy());
        assertThat(policy.expectedQuarantinedBatches()).isEqualTo(27);
        assertThat(policy.matches(totals(142_129, 2_324_399, 27, 545, 0, 0))).isTrue();
        assertThat(policy.matches(totals(142_129, 2_324_399, 27, 546, 0, 0))).isFalse();
    }

    private static SourceAcceptancePolicy basePolicy() {
        return new SourceAcceptancePolicy(
            Map.of("1650", SEAM_1650, "3330", SEAM_3330),
            BASE_ACCEPTED_BATCHES, BASE_ACCEPTED_OBSERVATIONS,
            BASE_QUARANTINED_BATCHES, BASE_QUARANTINED_OBSERVATIONS, 0, 0);
    }

    private static SourceArchiveExporter.Accumulator totals(
        long acceptedBatches,
        long acceptedObservations,
        long quarantinedBatches,
        long quarantinedObservations,
        long overlapBatches,
        long overlapObservations
    ) {
        SourceArchiveExporter.Accumulator accumulator = new SourceArchiveExporter.Accumulator();
        accumulator.batchCount = acceptedBatches;
        accumulator.observationCount = acceptedObservations;
        accumulator.quarantinedBatches = quarantinedBatches;
        accumulator.quarantinedObservations = quarantinedObservations;
        accumulator.overlapBatches = overlapBatches;
        accumulator.overlapObservations = overlapObservations;
        return accumulator;
    }

    private static ArchiveRecord record(
        String modelRoute,
        Instant responseReceivedAt
    ) {
        String digest = "a".repeat(64);
        return new ArchiveRecord(
            new ArchiveRecord.Batch(
                "827325854159", "1.0.0", UUID.nameUUIDFromBytes(digest.getBytes(StandardCharsets.UTF_8)),
                digest, "s3v1:" + digest, modelRoute, "3330".equals(modelRoute) ? "204000057" : "234000050",
                responseReceivedAt.minusMillis(200), responseReceivedAt.minusMillis(100),
                responseReceivedAt, 1, 200, 0, "SUCCESS_EMPTY", null, 0, 0, 0,
                "normalization-v1.0.0-s3-backfill", "adaptive-kst-v1.2.0", "S3_BACKFILL"),
            List.of());
    }
}
