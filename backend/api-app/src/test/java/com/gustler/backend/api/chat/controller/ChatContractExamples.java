package com.gustler.backend.api.chat.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ChatContractExamples {

    private static final String PROPERTY = "chat.contract.examples";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNode ROOT = load();

    private ChatContractExamples() {
    }

    static JsonNode availability() {
        return required(ROOT, "availability");
    }

    static JsonNode serverFrame(String name) {
        return required(required(ROOT, "serverFrames"), name);
    }

    static List<String> serverFrameNames() {
        return names(required(ROOT, "serverFrames"));
    }

    static JsonNode clientFrame(String name) {
        return required(required(ROOT, "clientFrames"), name);
    }

    static List<String> clientFrameNames() {
        return names(required(ROOT, "clientFrames"));
    }

    static String compact(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }

    static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    static List<String> fieldNames(JsonNode node) {
        return names(node);
    }

    private static List<String> names(JsonNode node) {
        return List.copyOf(node.propertyNames());
    }

    private static JsonNode required(JsonNode parent, String name) {
        JsonNode child = parent.get(name);
        if (child == null) {
            throw new IllegalStateException("contract example is missing: " + name);
        }
        return child;
    }

    private static JsonNode load() {
        String path = System.getProperty(PROPERTY);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(PROPERTY + " system property is required");
        }
        try {
            return MAPPER.readTree(Files.readString(Path.of(path)));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read contract examples", exception);
        }
    }
}
