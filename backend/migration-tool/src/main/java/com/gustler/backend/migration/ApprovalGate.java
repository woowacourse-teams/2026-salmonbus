package com.gustler.backend.migration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class ApprovalGate {

    public static final String SOURCE_EXPORT = "SOURCE_EXPORT";
    public static final String ACADEMY_SCHEMA = "ACADEMY_SCHEMA";
    public static final String ACADEMY_IMPORT = "ACADEMY_IMPORT";
    public static final String ACADEMY_ROLLBACK = "ACADEMY_ROLLBACK";
    public static final String ACADEMY_TEMP_CLEANUP = "ACADEMY_TEMP_CLEANUP";
    public static final String ACADEMY_SEED_APPLY = "ACADEMY_SEED_APPLY";
    public static final String ACADEMY_SEED_ROLLBACK = "ACADEMY_SEED_ROLLBACK";

    private static final String SCHEMA = "salmonbus-migration-approval-v1";
    private static final Set<String> KNOWN_FIELDS = Set.of(
        "action", "artifactSha256", "databaseIdentitySha256", "expiresAt", "schemaVersion");

    private final Clock clock;

    public ApprovalGate(
        Clock clock
    ) {
        this.clock = clock;
    }

    public void require(
        Path receipt,
        String action,
        String artifactSha256,
        String databaseIdentitySha256
    ) {
        SecureFiles.requirePrivateRegularFile(receipt);
        JsonNode root = CanonicalJson.readCanonical(receipt, "APPROVAL_RECEIPT_INVALID");
        requireExactFields(root);
        if (!SCHEMA.equals(text(root, "schemaVersion"))) {
            throw new MigrationException("APPROVAL_SCHEMA_INVALID");
        }
        if (!action.equals(text(root, "action"))) {
            throw new MigrationException("APPROVAL_ACTION_MISMATCH");
        }
        requireOptionalDigest(root, "artifactSha256", artifactSha256, "APPROVAL_ARTIFACT_MISMATCH");
        requireOptionalDigest(
            root, "databaseIdentitySha256", databaseIdentitySha256, "APPROVAL_DATABASE_MISMATCH");
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(text(root, "expiresAt"));
        } catch (RuntimeException e) {
            throw new MigrationException("APPROVAL_EXPIRY_INVALID", e);
        }
        if (!expiresAt.isAfter(clock.instant())) {
            throw new MigrationException("APPROVAL_EXPIRED");
        }
    }

    private static void requireExactFields(
        JsonNode root
    ) {
        if (!root.isObject()) {
            throw new MigrationException("APPROVAL_RECEIPT_INVALID");
        }
        Set<String> actual = new HashSet<>();
        root.properties().forEach(entry -> actual.add(entry.getKey()));
        if (!KNOWN_FIELDS.equals(actual)) {
            throw new MigrationException("APPROVAL_FIELDS_INVALID");
        }
    }

    private static void requireOptionalDigest(
        JsonNode root,
        String field,
        String expected,
        String code
    ) {
        JsonNode value = root.get(field);
        if (expected == null) {
            if (value != null && !value.isNull()) {
                throw new MigrationException(code);
            }
            return;
        }
        if (value == null || !value.isString() || !expected.equals(value.stringValue())) {
            throw new MigrationException(code);
        }
    }

    private static String text(
        JsonNode root,
        String field
    ) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MigrationException("APPROVAL_FIELD_INVALID");
        }
        return value.stringValue();
    }
}
