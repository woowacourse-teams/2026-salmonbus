package com.gustler.backend.api.chat.dto;

import com.gustler.backend.api.chat.domain.ChatMessage;
import java.time.Instant;

public record ChatMessageResponse(
    String id,
    String authorId,
    String nickname,
    String body,
    Instant createdAt
) {
    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
            message.id(), message.authorId(), message.nickname(), message.body(), message.createdAt()
        );
    }
}
