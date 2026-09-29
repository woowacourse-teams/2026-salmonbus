package com.gustler.backend.api.chat.domain;

import java.time.Instant;

public record ChatMessage(
    String id,
    String roomId,
    String clientMessageId,
    String authorId,
    String nickname,
    String body,
    Instant createdAt
) {
}
