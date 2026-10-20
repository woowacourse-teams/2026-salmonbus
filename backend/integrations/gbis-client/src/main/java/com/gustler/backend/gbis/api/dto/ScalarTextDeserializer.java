package com.gustler.backend.gbis.api.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 배차간격 필드의 숫자·문자열을 텍스트로 읽는다.
 * 구조 값은 JSON으로 남겨 도메인 경계에서 거절하게 한다.
 */
final class ScalarTextDeserializer extends ValueDeserializer<String> {
    @Override
    public String deserialize(
        JsonParser parser,
        DeserializationContext context
    ) {
        if (parser.currentToken().isStructStart()) {
            return context.readTree(parser).toString();
        }
        return parser.getString();
    }
}
