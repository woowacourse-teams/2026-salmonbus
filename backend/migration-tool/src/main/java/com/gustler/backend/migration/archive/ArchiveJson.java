package com.gustler.backend.migration.archive;

import com.gustler.backend.migration.MigrationException;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class ArchiveJson {

    private ArchiveJson() {
    }

    static void requireObjectWithFields(
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

    static String text(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MigrationException("ARCHIVE_FIELD_INVALID_" + codeOf(field));
        }
        return value.stringValue();
    }

    static String nullableText(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new MigrationException("ARCHIVE_FIELD_MISSING_" + codeOf(field));
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isString() || value.stringValue().isBlank()) {
            throw new MigrationException("ARCHIVE_FIELD_INVALID_" + codeOf(field));
        }
        return value.stringValue();
    }

    static int integer(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new MigrationException("ARCHIVE_FIELD_INVALID_" + codeOf(field));
        }
        return value.intValue();
    }

    static long longValue(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new MigrationException("ARCHIVE_FIELD_INVALID_" + codeOf(field));
        }
        return value.longValue();
    }

    static Integer nullableInteger(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new MigrationException("ARCHIVE_FIELD_MISSING_" + codeOf(field));
        }
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new MigrationException("ARCHIVE_FIELD_INVALID_" + codeOf(field));
        }
        return value.intValue();
    }

    static boolean bool(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new MigrationException("ARCHIVE_FIELD_INVALID_" + codeOf(field));
        }
        return value.booleanValue();
    }

    static JsonNode object(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new MigrationException("ARCHIVE_FIELD_INVALID_" + codeOf(field));
        }
        return value;
    }

    static JsonNode array(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new MigrationException("ARCHIVE_FIELD_INVALID_" + codeOf(field));
        }
        return value;
    }

    private static String codeOf(
        String field
    ) {
        return field.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }
}
