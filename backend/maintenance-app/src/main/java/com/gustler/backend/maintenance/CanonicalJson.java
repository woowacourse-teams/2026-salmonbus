package com.gustler.backend.maintenance;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/** 유지보수 명령의 JSON 출력을 일관된 키 순서로 기록한다. */
public final class CanonicalJson {
    private static final ObjectMapper WRITER = JsonMapper.builder()
        .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private CanonicalJson() {
    }

    public static String stringOf(Object value) {
        return WRITER.writeValueAsString(value);
    }
}
