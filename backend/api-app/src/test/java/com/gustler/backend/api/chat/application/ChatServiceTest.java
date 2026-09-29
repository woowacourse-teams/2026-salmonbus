package com.gustler.backend.api.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.chat.domain.ChatMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    @Test
    void 저장소가_확정한_뒤에만_메시지_결과를_돌려준다() {
        RecordingRepository repository = new RecordingRepository();
        Instant now = Instant.parse("2026-09-29T13:21:34.127Z");
        ChatService service = new ChatService(repository, Clock.fixed(now, ZoneOffset.UTC));

        ChatMessageRepository.SaveResult result = service.send(
            "204000057",
            "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            new ChatIdentity("author", "졸린 범계역"),
            "지금 자리 여유 있어요"
        );

        assertThat(repository.saved).hasSize(1);
        assertThat(result.status()).isEqualTo(ChatMessageRepository.SaveResult.Status.CREATED);
        assertThat(result.message().createdAt()).isEqualTo(now);
        assertThat(result.message().id()).isEqualTo(RecordingRepository.NEXT_ID);
    }

    @Test
    void 최근_이력은_항상_50개만_요청한다() {
        RecordingRepository repository = new RecordingRepository();
        ChatService service = new ChatService(repository, Clock.systemUTC());

        service.recent("204000057");

        assertThat(repository.recentLimit).isEqualTo(50);
    }

    private static final class RecordingRepository implements ChatMessageRepository {
        private static final String NEXT_ID = "68db00000000000000000003";

        private final List<ChatMessage> saved = new ArrayList<>();
        private int recentLimit;

        @Override
        public String nextId() {
            return NEXT_ID;
        }

        @Override
        public SaveResult save(ChatMessage message) {
            saved.add(message);
            return new SaveResult(SaveResult.Status.CREATED, message);
        }

        @Override
        public List<ChatMessage> findRecent(String roomId, final int limit) {
            recentLimit = limit;
            return List.of();
        }
    }
}
