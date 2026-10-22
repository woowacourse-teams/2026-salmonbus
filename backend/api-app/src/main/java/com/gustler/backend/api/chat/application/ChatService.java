package com.gustler.backend.api.chat.application;

import com.gustler.backend.api.chat.application.ChatMessageRepository.SaveResult;
import com.gustler.backend.api.chat.domain.ChatMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 방별 최근 메시지 조회와 전송할 메시지의 저장을 조율한다.
 * 같은 클라이언트 메시지의 재전송과 내용 충돌을 구분한다.
 */
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

    /**
     * 연결 신원으로 해당 방에 메시지를 저장한다. 호출자가 중복·충돌 여부를 구별할 수 있도록 저장 결과를 반환한다.
     */
    public SaveResult send(
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
