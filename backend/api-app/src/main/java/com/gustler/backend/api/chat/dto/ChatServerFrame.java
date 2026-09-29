package com.gustler.backend.api.chat.dto;

import java.util.List;

public sealed interface ChatServerFrame {

    int v();

    String type();

    record SessionReady(
        int v,
        String type,
        String authorId,
        String nickname,
        int maxBodyCodePoints
    ) implements ChatServerFrame {
    }

    record HistoryBatch(int v, String type, List<ChatMessageResponse> messages) implements ChatServerFrame {
    }

    record HistoryEnd(int v, String type) implements ChatServerFrame {
    }

    record MessageAck(
        int v,
        String type,
        String clientMessageId,
        boolean duplicate,
        ChatMessageResponse message
    ) implements ChatServerFrame {
    }

    record MessageCreated(int v, String type, ChatMessageResponse message) implements ChatServerFrame {
    }

    record Error(
        int v,
        String type,
        String requestId,
        String code,
        String message,
        Long retryAfterMs,
        boolean fatal
    ) implements ChatServerFrame {
    }
}
