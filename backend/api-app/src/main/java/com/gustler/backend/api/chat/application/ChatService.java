package com.gustler.backend.api.chat.application;

import com.gustler.backend.api.chat.domain.ChatMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

public final class ChatService {

    public static final int HISTORY_SIZE = 50;
    public static final int MAX_BODY_CODE_POINTS = 200;

    private final ChatMessageRepository repository;
    private final Clock clock;

    public ChatService(ChatMessageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<ChatMessage> recent(String roomId) {
        return repository.findRecent(roomId, HISTORY_SIZE);
    }

    public ChatMessageRepository.SaveResult send(
        String roomId,
        String clientMessageId,
        ChatIdentity identity,
        String body
    ) {
        Instant createdAt = Instant.ofEpochMilli(clock.millis());
        ChatMessage message = new ChatMessage(
            repository.nextId(),
            roomId,
            clientMessageId,
            identity.authorId(),
            identity.nickname(),
            body,
            createdAt
        );
        return repository.save(message);
    }
}
