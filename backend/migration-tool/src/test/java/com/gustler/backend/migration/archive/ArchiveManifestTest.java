package com.gustler.backend.migration.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.MigrationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArchiveManifestTest {

    @Test
    void bindsTerminalDatasetSealToConfirmedSourceClosure() {
        ArchiveManifest manifest = terminalManifest(
            ArchiveManifest.TerminalFreeze.EXPECTED_FINAL_INVENTORY_SHA256);

        assertThat(manifest.terminalFreeze().terminalPartitionInventorySha256())
            .isEqualTo(ArchiveManifest.TerminalFreeze.EXPECTED_TERMINAL_PARTITION_INVENTORY_SHA256);
        assertThat(manifest.terminalFreeze().immutableBaseInventorySha256())
            .isEqualTo(ArchiveManifest.TerminalFreeze.EXPECTED_IMMUTABLE_BASE_INVENTORY_SHA256);
        assertThat(manifest.terminalFreeze().sourceClosureSha256())
            .isEqualTo(ArchiveManifest.TerminalFreeze.EXPECTED_SOURCE_CLOSURE_SHA256);
    }

    @Test
    void rejectsTerminalSealWhoseFullInventoryDiffersFromManifest() {
        assertThatThrownBy(() -> terminalManifest("9".repeat(64)))
            .isInstanceOf(MigrationException.class)
            .hasMessage("ARCHIVE_TERMINAL_INVENTORY_MISMATCH");
    }

    private static ArchiveManifest terminalManifest(String inventorySha256) {
        ArchiveManifest.TerminalFreeze freeze = new ArchiveManifest.TerminalFreeze(
            "1".repeat(64),
            "2".repeat(64),
            ArchiveManifest.TerminalFreeze.EXPECTED_FINAL_INVENTORY_SHA256,
            ArchiveManifest.TerminalFreeze.EXPECTED_TERMINAL_PARTITION_INVENTORY_SHA256,
            ArchiveManifest.TerminalFreeze.EXPECTED_IMMUTABLE_BASE_INVENTORY_SHA256,
            ArchiveManifest.TerminalFreeze.EXPECTED_SOURCE_CLOSURE_SHA256,
            ArchiveManifest.TerminalFreeze.EXPECTED_COLLECTOR_CODE_SHA256_BASE64,
            ArchiveManifest.TerminalFreeze.EXPECTED_DISABLED_AT,
            Instant.parse("2026-09-02T13:22:53.278Z"));
        return new ArchiveManifest(
            "TERMINAL_DELTA",
            "3".repeat(64),
            "fixture-exporter",
            Instant.parse("2026-09-02T13:23:40.988Z"),
            new ArchiveManifest.Inventory(
                "object-inventory-v1", inventorySha256, 0, 0, 0, List.of()),
            "normalization-v1.0.0-s3-backfill",
            true,
            freeze,
            List.of(),
            List.of(),
            new ArchiveManifest.Summary(
                0, 0, 0, 0, 0, 0, 0, null, null,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
    }
}
