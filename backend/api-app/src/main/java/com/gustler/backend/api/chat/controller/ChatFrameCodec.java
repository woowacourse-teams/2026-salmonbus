package com.gustler.backend.api.chat.controller;

import com.gustler.backend.api.chat.dto.ChatServerFrame;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ChatFrameCodec {

    public static final int PROTOCOL_VERSION = 1;
    private static final String MESSAGE_SEND = "message.send";

    private final ObjectMapper objectMapper;

    public ChatFrameCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ClientFrame decode(String payload) {
        JsonNode root = objectMapper.readTree(payload);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("frame must be an object");
        }
        final int version = requiredInt(root, "v");
        if (version != PROTOCOL_VERSION) {
            return new UnsupportedProtocol(version, messageIdOfSend(root));
        }
        String type = requiredText(root, "type");
        return switch (type) {
            case "session.start" -> new SessionStart(version, requiredUuid(root, "clientSessionId"));
            case MESSAGE_SEND -> new MessageSend(
                version,
                requiredUuid(root, "clientMessageId").toString(),
                requiredText(root, "body")
            );
            default -> throw new IllegalArgumentException("unsupported frame type");
        };
    }

    public String encode(ChatServerFrame frame) {
        return objectMapper.writeValueAsString(frame);
    }

    private int requiredInt(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isInt()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return value.intValue();
    }

    private String requiredText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return value.stringValue();
    }

    private UUID requiredUuid(JsonNode root, String name) {
        UUID value = UUID.fromString(requiredText(root, name));
        if (value.version() != 4) {
            throw new IllegalArgumentException(name + " must be UUID v4");
        }
        return value;
    }

    private String messageIdOfSend(JsonNode root) {
        JsonNode type = root.get("type");
        JsonNode messageId = root.get("clientMessageId");
        if (type == null || !type.isString() || !MESSAGE_SEND.equals(type.stringValue())) {
            return null;
        }
        if (messageId == null || !messageId.isString()) {
            return null;
        }
        return messageId.stringValue();
    }

    public sealed interface ClientFrame {
        int version();
    }

    public record SessionStart(int version, UUID clientSessionId) implements ClientFrame {
    }

    public record MessageSend(int version, String clientMessageId, String body) implements ClientFrame {
    }

    public record UnsupportedProtocol(int version, String requestId) implements ClientFrame {
    }
}
