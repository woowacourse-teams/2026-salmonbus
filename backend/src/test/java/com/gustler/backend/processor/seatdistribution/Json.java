package com.gustler.backend.processor.seatdistribution;

import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** 테스트가 설명 파일을 JSON 으로 쓴다. */
final class Json {

    private Json() {
    }

    static String of(
        Map<String, Object> value
    ) {
        return new ObjectMapper().writeValueAsString(value);
    }
}
