package com.gustler.backend.gbis.api.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

final class ScalarTextDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(
        JsonParser parser,
        DeserializationContext context
    ) {
        if (parser.currentToken().isStructStart()) {
            parser.skipChildren();
            return null;
        }
        return parser.getString();
    }
}
