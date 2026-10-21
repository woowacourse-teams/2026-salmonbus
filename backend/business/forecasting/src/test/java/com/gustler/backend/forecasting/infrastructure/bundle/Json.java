package com.gustler.backend.forecasting.infrastructure.bundle;

import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** 테스트에서 설명 파일을 JSON 으로 작성한다. */
final class Json {
    private Json() {
    }

    /** 적재에 필요한 정규 형식으로 작성한다. 항목을 이름순으로 정렬하고 빈칸을 넣지 않는다. */
    static String of(
        Map<String, Object> value
    ) {
        return new ObjectMapper()
            .writer()
            .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writeValueAsString(value);
    }
}
