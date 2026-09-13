package com.gustler.backend.migration.source;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.Sha256;
import com.gustler.backend.migration.archive.ArchiveManifest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class TerminalFreezeReceipt {

    private static final Set<String> FIELDS = Set.of(
        "collectorCodeSha256Base64", "disabledAt", "drainedAt", "finalInventorySha256",
        "immutableBaseInventorySha256", "scheduleIdentitySha256", "schemaVersion", "scheduleState",
        "sourceAccount", "sourceClosureSha256", "terminalPartitionInventorySha256");

    private TerminalFreezeReceipt() {
    }

    static ArchiveManifest.TerminalFreeze read(
        Path path,
        SourceInventory inventory
    ) {
        SecureFiles.requirePrivateRegularFile(path);
        JsonNode root = CanonicalJson.readCanonical(path, "TERMINAL_FREEZE_RECEIPT_INVALID");
        requireFields(root);
        Instant disabledAt = instant(root, "disabledAt");
        Instant drainedAt = instant(root, "drainedAt");
        if (!"salmonbus-source-terminal-freeze-v1".equals(text(root, "schemaVersion"))
            || !"DISABLED".equals(text(root, "scheduleState"))
            || !inventory.sourceAccountId().equals(text(root, "sourceAccount"))
            || !inventory.inventorySha256().equals(text(root, "finalInventorySha256"))
            || !ArchiveManifest.TerminalFreeze.EXPECTED_IMMUTABLE_BASE_INVENTORY_SHA256.equals(
                inventory.previousInventorySha256())
            || !ArchiveManifest.TerminalFreeze.EXPECTED_TERMINAL_PARTITION_INVENTORY_SHA256.equals(
                inventory.selectedInventorySha256())
            || inventory.entries().stream().anyMatch(entry -> entry.lastModified().isAfter(drainedAt))
            || drainedAt.isAfter(inventory.cutoffAt())) {
            throw new MigrationException("TERMINAL_FREEZE_RECEIPT_IDENTITY_MISMATCH");
        }
        try {
            return new ArchiveManifest.TerminalFreeze(
                text(root, "scheduleIdentitySha256"),
                Sha256.of(path),
                text(root, "finalInventorySha256"),
                text(root, "terminalPartitionInventorySha256"),
                text(root, "immutableBaseInventorySha256"),
                text(root, "sourceClosureSha256"),
                text(root, "collectorCodeSha256Base64"),
                disabledAt,
                drainedAt);
        } catch (RuntimeException e) {
            if (e instanceof MigrationException migration) {
                throw migration;
            }
            throw new MigrationException("TERMINAL_FREEZE_RECEIPT_TIME_INVALID", e);
        }
    }

    private static void requireFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new MigrationException("TERMINAL_FREEZE_RECEIPT_INVALID");
        }
        Set<String> actual = new HashSet<>();
        root.properties().forEach(entry -> actual.add(entry.getKey()));
        if (!FIELDS.equals(actual)) {
            throw new MigrationException("TERMINAL_FREEZE_RECEIPT_FIELDS_INVALID");
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MigrationException("TERMINAL_FREEZE_RECEIPT_FIELD_INVALID");
        }
        return value.stringValue();
    }

    private static Instant instant(JsonNode root, String field) {
        try {
            return Instant.parse(text(root, field));
        } catch (RuntimeException e) {
            throw new MigrationException("TERMINAL_FREEZE_RECEIPT_TIME_INVALID", e);
        }
    }
}
