package com.gustler.backend.api.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.chat.dto.ChatMessageResponse;
import com.gustler.backend.api.chat.dto.ChatServerFrame;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class ChatFrameCodecTest {

    private static final ChatMessageResponse HISTORY_FIRST = new ChatMessageResponse(
        "68db00000000000000000001",
        "q2Xl9Rk3TbS1c0vYmN8wJA",
        "수다스러운 안양역",
        "야탑역에서 방금 탔어요",
        Instant.parse("2026-09-29T13:20:02Z")
    );
    private static final ChatMessageResponse HISTORY_SECOND = new ChatMessageResponse(
        "68db00000000000000000002",
        "Y4O3sB0Z_Y8WQm7ywwBqHA",
        "졸린 범계역",
        "뒤쪽은 자리 있나요?",
        Instant.parse("2026-09-29T13:20:41.503Z")
    );
    private static final ChatMessageResponse SENT = new ChatMessageResponse(
        "68db00000000000000000003",
        "Y4O3sB0Z_Y8WQm7ywwBqHA",
        "졸린 범계역",
        "지금 자리 여유 있어요",
        Instant.parse("2026-09-29T13:21:34.127Z")
    );
    private static final String MESSAGE_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private static final Map<String, ChatServerFrame> SERVER_FRAMES = Map.of(
        "session.ready", new ChatServerFrame.SessionReady(
            1, "session.ready", "Y4O3sB0Z_Y8WQm7ywwBqHA", "졸린 범계역", 200
        ),
        "history.batch", new ChatServerFrame.HistoryBatch(
            1, "history.batch", List.of(HISTORY_FIRST, HISTORY_SECOND)
        ),
        "history.end", new ChatServerFrame.HistoryEnd(1, "history.end"),
        "message.ack", new ChatServerFrame.MessageAck(1, "message.ack", MESSAGE_ID, false, SENT),
        "message.created", new ChatServerFrame.MessageCreated(1, "message.created", SENT),
        "error.rateLimited", new ChatServerFrame.Error(
            1, "error", MESSAGE_ID, "RATE_LIMITED", "잠시 후 다시 보내 주세요.", 1_000L, false
        ),
        "error.invalidBody", new ChatServerFrame.Error(
            1, "error", MESSAGE_ID, "INVALID_BODY", "메시지는 1자 이상 200자 이하여야 합니다.", null, false
        ),
        "error.unavailable", new ChatServerFrame.Error(
            1, "error", null, "CHAT_UNAVAILABLE", "채팅을 잠시 사용할 수 없습니다.", null, false
        ),
        "error.helloRequired", new ChatServerFrame.Error(
            1, "error", null, "HELLO_REQUIRED", "세션 시작 시간이 지났습니다.", null, true
        )
    );

    private static final Map<String, ChatFrameCodec.ClientFrame> CLIENT_FRAMES = Map.of(
        "session.start", new ChatFrameCodec.SessionStart(1, UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")),
        "message.send", new ChatFrameCodec.MessageSend(1, MESSAGE_ID, "지금 자리 여유 있어요")
    );

    private final ChatFrameCodec codec = new ChatFrameCodec(JsonMapper.builder().findAndAddModules().build());

    static List<String> serverFrameNames() {
        return ChatContractExamples.serverFrameNames();
    }

    static List<String> clientFrameNames() {
        return ChatContractExamples.clientFrameNames();
    }

    @Test
    void 계약_예시의_프레임마다_대응하는_값이_있다() {
        // then
        assertThat(SERVER_FRAMES.keySet()).containsExactlyInAnyOrderElementsOf(serverFrameNames());
        assertThat(CLIENT_FRAMES.keySet()).containsExactlyInAnyOrderElementsOf(clientFrameNames());
    }

    @ParameterizedTest
    @MethodSource("serverFrameNames")
    void 서버_프레임을_계약_예시와_키_순서까지_같게_직렬화한다(String name) {
        // when
        String encoded = codec.encode(SERVER_FRAMES.get(name));

        // then
        assertThat(encoded).isEqualTo(ChatContractExamples.compact(ChatContractExamples.serverFrame(name)));
    }

    @ParameterizedTest
    @MethodSource("clientFrameNames")
    void 계약_예시의_클라이언트_프레임을_읽는다(String name) {
        // when
        ChatFrameCodec.ClientFrame decoded = codec.decode(
            ChatContractExamples.compact(ChatContractExamples.clientFrame(name))
        );

        // then
        assertThat(decoded).isEqualTo(CLIENT_FRAMES.get(name));
    }

    @Test
    void UUID_v4가_아닌_클라이언트_ID를_거절한다() {
        assertThatThrownBy(() -> codec.decode("""
            {"v":1,"type":"message.send","clientMessageId":"00000000-0000-1000-8000-000000000000","body":"안녕"}
            """))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 버전이_1이_아니면_모양과_상관없이_지원하지_않는_프로토콜로_읽는다() {
        // when
        ChatFrameCodec.ClientFrame send = codec.decode("""
            {"v":2,"type":"message.send","clientMessageId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","text":"안녕"}
            """);
        ChatFrameCodec.ClientFrame unknown = codec.decode("""
            {"v":2,"type":"session.resume"}
            """);

        // then
        assertThat(send).isEqualTo(new ChatFrameCodec.UnsupportedProtocol(2, MESSAGE_ID));
        assertThat(unknown).isEqualTo(new ChatFrameCodec.UnsupportedProtocol(2, null));
    }
}
