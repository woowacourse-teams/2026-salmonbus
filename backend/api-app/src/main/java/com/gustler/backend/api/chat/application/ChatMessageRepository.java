package com.gustler.backend.api.chat.application;

import com.gustler.backend.api.chat.domain.ChatMessage;
import java.util.List;

public interface ChatMessageRepository {

    String nextId();

    SaveResult save(ChatMessage message);

    List<ChatMessage> findRecent(String roomId, int limit);

    record SaveResult(Status status, ChatMessage message) {

        public enum Status {
            CREATED,
            DUPLICATE,
            CONFLICT
        }
    }
}
