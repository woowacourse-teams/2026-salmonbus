package com.gustler.backend.migration.source;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.Sha256;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** object key가 들어 있는 개인 측 전용 파일이다. archive/RDS/receipt로 복사하지 않는다. */
public record SourceInventory(
    String sourceAccountId,
    String bucket,
    String region,
    Instant cutoffAt,
    String previousInventorySha256,
    String inventorySha256,
    List<SourceObjectStore.ObjectInfo> entries,
    List<String> selectedKeyDigests
) {

    public static final String SCHEMA_VERSION = "salmonbus-private-source-inventory-v1";

    private static final Set<String> FIELDS = Set.of(
        "bucket", "cutoffAt", "entries", "inventorySha256", "previousInventorySha256",
        "region", "schemaVersion", "selectedKeyDigests", "sourceAccountId");
    private static final Set<String> ENTRY_FIELDS = Set.of(
        "etag", "key", "kind", "lastModified", "size");
    private static final Comparator<SourceObjectStore.ObjectInfo> KEY_ORDER =
        Comparator.comparing(SourceObjectStore.ObjectInfo::key, SourceInventory::compareUtf8);

    public SourceInventory {
        entries = entries.stream().sorted(KEY_ORDER).toList();
        selectedKeyDigests = selectedKeyDigests.stream().sorted().distinct().toList();
        if (sourceAccountId == null || !sourceAccountId.matches("[0-9]{12}")
            || bucket == null || bucket.isBlank() || region == null || region.isBlank() || cutoffAt == null) {
            throw new MigrationException("SOURCE_INVENTORY_IDENTITY_INVALID");
        }
        if (previousInventorySha256 != null) {
            Sha256.requireDigest(previousInventorySha256, "SOURCE_PREVIOUS_INVENTORY_DIGEST_INVALID");
        }
        Set<String> keys = new HashSet<>();
        for (SourceObjectStore.ObjectInfo entry : entries) {
            if (!keys.add(entry.key()) || entry.size() < 0 || entry.lastModified().isAfter(cutoffAt)
                || entry.etag() == null || entry.etag().isBlank()) {
                throw new MigrationException("SOURCE_INVENTORY_ENTRY_INVALID");
            }
        }
        Set<String> availableDigests = entries.stream()
            .map(SourceObjectStore.ObjectInfo::key)
            .map(Sha256::of)
            .collect(java.util.stream.Collectors.toSet());
        if (!availableDigests.containsAll(selectedKeyDigests)) {
            throw new MigrationException("SOURCE_SELECTED_INVENTORY_ENTRY_MISSING");
        }
        String calculated = digestOf(entries);
        if (inventorySha256 == null) {
            inventorySha256 = calculated;
        } else if (!calculated.equals(inventorySha256)) {
            throw new MigrationException("SOURCE_INVENTORY_DIGEST_MISMATCH");
        }
    }

    public void writeTo(
        Path path
    ) {
        SecureFiles.writeNew(path, CanonicalJson.bytesOf(toMap()));
    }

    public static SourceInventory read(
        Path path
    ) {
        SecureFiles.requirePrivateRegularFile(path);
        JsonNode root = CanonicalJson.readCanonical(path, "SOURCE_INVENTORY_JSON_INVALID");
        ArchiveFields.require(root, FIELDS, "SOURCE_INVENTORY_FIELDS_INVALID");
        if (!SCHEMA_VERSION.equals(text(root, "schemaVersion"))) {
            throw new MigrationException("SOURCE_INVENTORY_SCHEMA_INVALID");
        }
        List<SourceObjectStore.ObjectInfo> entries = new ArrayList<>();
        JsonNode entryNodes = root.get("entries");
        if (entryNodes == null || !entryNodes.isArray()) {
            throw new MigrationException("SOURCE_INVENTORY_ENTRIES_INVALID");
        }
        for (JsonNode entry : entryNodes) {
            ArchiveFields.require(entry, ENTRY_FIELDS, "SOURCE_INVENTORY_ENTRY_FIELDS_INVALID");
            try {
                entries.add(new SourceObjectStore.ObjectInfo(
                    SourceObjectStore.Kind.valueOf(text(entry, "kind")),
                    text(entry, "key"),
                    text(entry, "etag"),
                    integral(entry, "size"),
                    Instant.parse(text(entry, "lastModified"))));
            } catch (IllegalArgumentException e) {
                throw new MigrationException("SOURCE_INVENTORY_ENTRY_INVALID", e);
            }
        }
        try {
            return new SourceInventory(
                text(root, "sourceAccountId"),
                text(root, "bucket"),
                text(root, "region"),
                Instant.parse(text(root, "cutoffAt")),
                nullableText(root, "previousInventorySha256"),
                text(root, "inventorySha256"),
                entries,
                textArray(root, "selectedKeyDigests"));
        } catch (DateTimeParseException e) {
            throw new MigrationException("SOURCE_INVENTORY_TIME_INVALID", e);
        }
    }

    public long count(
        SourceObjectStore.Kind kind
    ) {
        return selectedEntries().stream().filter(entry -> entry.kind() == kind).count();
    }

    public List<SourceObjectStore.ObjectInfo> selectedEntries() {
        Set<String> selected = Set.copyOf(selectedKeyDigests);
        return entries.stream().filter(entry -> selected.contains(Sha256.of(entry.key()))).toList();
    }

    public String selectedInventorySha256() {
        return digestOf(selectedEntries());
    }

    private Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("sourceAccountId", sourceAccountId);
        root.put("bucket", bucket);
        root.put("region", region);
        root.put("cutoffAt", cutoffAt.toString());
        root.put("previousInventorySha256", previousInventorySha256);
        root.put("inventorySha256", inventorySha256);
        root.put("entries", entries.stream().map(SourceInventory::entryMap).toList());
        root.put("selectedKeyDigests", selectedKeyDigests);
        return root;
    }

    private static Map<String, Object> entryMap(
        SourceObjectStore.ObjectInfo entry
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", entry.kind().name());
        value.put("key", entry.key());
        value.put("etag", entry.etag());
        value.put("size", entry.size());
        value.put("lastModified", entry.lastModified().toString());
        return value;
    }

    private static String digestOf(
        List<SourceObjectStore.ObjectInfo> entries
    ) {
        StringBuilder canonical = new StringBuilder();
        entries.stream().sorted(KEY_ORDER).forEach(entry -> canonical
            .append(entry.key()).append('\t')
            .append(entry.etag()).append('\t')
            .append(entry.size()).append('\n'));
        return Sha256.of(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int compareUtf8(
        String first,
        String second
    ) {
        byte[] left = first.getBytes(StandardCharsets.UTF_8);
        byte[] right = second.getBytes(StandardCharsets.UTF_8);
        int common = Math.min(left.length, right.length);
        for (int index = 0; index < common; index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static String text(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MigrationException("SOURCE_INVENTORY_FIELD_INVALID");
        }
        return value.stringValue();
    }

    private static String nullableText(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new MigrationException("SOURCE_INVENTORY_FIELD_INVALID");
        }
        return value.isNull() ? null : text(node, field);
    }

    private static long integral(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new MigrationException("SOURCE_INVENTORY_FIELD_INVALID");
        }
        return value.longValue();
    }

    private static List<String> textArray(
        JsonNode node,
        String field
    ) {
        JsonNode values = node.get(field);
        if (values == null || !values.isArray()) {
            throw new MigrationException("SOURCE_INVENTORY_FIELD_INVALID");
        }
        List<String> output = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isString() || !Sha256.isDigest(value.stringValue())) {
                throw new MigrationException("SOURCE_INVENTORY_FIELD_INVALID");
            }
            output.add(value.stringValue());
        }
        return output;
    }

    private static final class ArchiveFields {

        private ArchiveFields() {
        }

        private static void require(
            JsonNode node,
            Set<String> fields,
            String code
        ) {
            if (node == null || !node.isObject()) {
                throw new MigrationException(code);
            }
            Set<String> actual = new HashSet<>();
            node.properties().forEach(entry -> actual.add(entry.getKey()));
            if (!fields.equals(actual)) {
                throw new MigrationException(code);
            }
        }
    }
}
