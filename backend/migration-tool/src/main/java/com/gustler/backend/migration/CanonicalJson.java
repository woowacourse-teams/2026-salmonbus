package com.gustler.backend.migration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

public final class CanonicalJson {

    private static final ObjectMapper READER = new ObjectMapper();
    private static final ObjectMapper WRITER = JsonMapper.builder()
        .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private CanonicalJson() {
    }

    public static byte[] bytesOf(
        Object value
    ) {
        return WRITER.writeValueAsBytes(value);
    }

    public static String stringOf(
        Object value
    ) {
        return WRITER.writeValueAsString(value);
    }

    public static JsonNode parse(
        byte[] bytes,
        String errorCode
    ) {
        strictUtf8(bytes, errorCode);
        try {
            return READER.readTree(bytes);
        } catch (RuntimeException e) {
            throw new MigrationException(errorCode, e);
        }
    }

    public static JsonNode readCanonical(
        Path path,
        String errorCode
    ) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new MigrationException(errorCode, e);
        }
        JsonNode root = parse(bytes, errorCode);
        String actual = strictUtf8(bytes, errorCode).strip();
        if (!WRITER.writeValueAsString(root).equals(actual)) {
            throw new MigrationException("NON_CANONICAL_JSON");
        }
        return root;
    }

    public static void writeCanonical(
        Path path,
        Object value
    ) {
        SecureFiles.writeNew(path, bytesOf(value));
    }

    private static String strictUtf8(
        byte[] bytes,
        String errorCode
    ) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            throw new MigrationException(errorCode, e);
        }
    }
}
