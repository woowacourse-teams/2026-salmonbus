package com.gustler.backend.api.chat.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 채팅 서버가 전송하는 버전을 포함한 프레임 계약이다.
 * 기존 type·필드·직렬화 형태를 유지한다.
 */
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
        /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
        public HistoryBatch {
            if (messages != null) {
                messages = Collections.unmodifiableList(new ArrayList<>(messages));
            }
        }
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
