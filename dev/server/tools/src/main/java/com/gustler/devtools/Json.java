package com.gustler.devtools;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class Json {
    static final JsonMapper MAPPER =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .build();

    private Json() {}

    static JsonNode read(byte[] bytes) {
        return MAPPER.readTree(bytes);
    }

    static JsonNode read(Path path) throws Exception {
        return read(Files.readAllBytes(path));
    }

    static byte[] bytes(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    static void output(Map<String, ?> value) {
        System.out.println(MAPPER.writeValueAsString(value));
    }
}
