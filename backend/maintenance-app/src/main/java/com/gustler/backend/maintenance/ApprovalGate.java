package com.gustler.backend.maintenance;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 실행 대상과 승인 자료의 범위를 대조해 유지보수 명령의 실행 조건을 확인한다.
 */
public final class ApprovalGate {
    public static final String ACADEMY_TRIP_QUALITY_REBUILD = "ACADEMY_TRIP_QUALITY_REBUILD";

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
            throw new MaintenanceException("APPROVAL_SCHEMA_INVALID");
        }
        if (!action.equals(text(root, "action"))) {
            throw new MaintenanceException("APPROVAL_ACTION_MISMATCH");
        }
        requireOptionalDigest(root, "artifactSha256", artifactSha256, "APPROVAL_ARTIFACT_MISMATCH");
        requireOptionalDigest(
            root, "databaseIdentitySha256", databaseIdentitySha256, "APPROVAL_DATABASE_MISMATCH");
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(text(root, "expiresAt"));
        } catch (RuntimeException e) {
            throw new MaintenanceException("APPROVAL_EXPIRY_INVALID", e);
        }
        if (!expiresAt.isAfter(clock.instant())) {
            throw new MaintenanceException("APPROVAL_EXPIRED");
        }
    }

    private static void requireExactFields(
        JsonNode root
    ) {
        if (!root.isObject()) {
            throw new MaintenanceException("APPROVAL_RECEIPT_INVALID");
        }
        Set<String> actual = new HashSet<>();
        root.properties().forEach(entry -> actual.add(entry.getKey()));
        if (!KNOWN_FIELDS.equals(actual)) {
            throw new MaintenanceException("APPROVAL_FIELDS_INVALID");
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
                throw new MaintenanceException(code);
            }
            return;
        }
        if (!matchesExpectedText(value, expected)) {
            throw new MaintenanceException(code);
        }
    }

    private static boolean matchesExpectedText(JsonNode value, String expected) {
        if (value == null || !value.isString()) {
            return false;
        }
        return expected.equals(value.stringValue());
    }

    private static String text(
        JsonNode root,
        String field
    ) {
        JsonNode value = root.get(field);
        if (!hasText(value)) {
            throw new MaintenanceException("APPROVAL_FIELD_INVALID");
        }
        return value.stringValue();
    }

    private static boolean hasText(JsonNode value) {
        if (value == null || !value.isString()) {
            return false;
        }
        return !value.stringValue().isBlank();
    }
}
