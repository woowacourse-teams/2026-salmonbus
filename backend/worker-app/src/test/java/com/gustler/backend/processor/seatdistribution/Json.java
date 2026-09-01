package com.gustler.backend.processor.seatdistribution;

import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** 테스트가 설명 파일을 JSON 으로 쓴다. */
final class Json {

    private Json() {
    }

    /** 적재가 요구하는 정규 모양으로 쓴다. 항목을 이름순으로 놓고 빈칸을 안 넣는다. */
    static String of(
        Map<String, Object> value
    ) {
        return new ObjectMapper()
            .writer()
            .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writeValueAsString(value);
    }
}
