package com.gustler.backend.api.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gustler.backend.api.chat.application.ChatIdentityService;
import com.gustler.backend.api.chat.application.ChatMessageRepository;
import com.gustler.backend.api.chat.application.ChatTestRoutes;
import com.gustler.backend.api.chat.application.ChatService;
import com.gustler.backend.api.chat.domain.ChatMessage;
import jakarta.websocket.Session;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.apache.tomcat.websocket.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class ChatWebSocketHandlerTest {

    private static final String SESSION_START = """
        {"v":1,"type":"session.start","clientSessionId":"0f8fad5b-d9cb-469f-a165-70867728950e"}
        """;
    private static final String OTHER_SESSION_START = """
        {"v":1,"type":"session.start","clientSessionId":"6ba7b810-9dad-41d1-80b4-00c04fd430c8"}
        """;
    private static final String EXAMPLE_MESSAGE_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private final Map<String, CloseStatus> closed = new HashMap<>();

    @Test
    void 저장한_뒤_ACK하고_같은_방에_방송하며_재전송은_중복_ACK만_보낸다() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        InMemoryRepository repository = new InMemoryRepository();
        ChatWebSocketHandler handler = handler(repository, clock);
        List<String> senderFrames = new ArrayList<>();
        List<String> peerFrames = new ArrayList<>();
        WebSocketSession sender = session("sender", "204000057", senderFrames);
        WebSocketSession peer = session("peer", "204000057", peerFrames);
        handler.afterConnectionEstablished(sender);
        handler.afterConnectionEstablished(peer);
        handler.handleTextMessage(sender, new TextMessage(SESSION_START));
        handler.handleTextMessage(peer, new TextMessage("""
            {"v":1,"type":"session.start","clientSessionId":"6ba7b810-9dad-41d1-80b4-00c04fd430c8"}
            """));
        senderFrames.clear();
        peerFrames.clear();

        String send = """
            {"v":1,"type":"message.send","clientMessageId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","body":"지금 자리 여유 있어요"}
            """;
        handler.handleTextMessage(sender, new TextMessage(send));

        assertThat(repository.saveCalls).isEqualTo(1);
        assertThat(senderFrames).hasSize(2);
        assertThat(senderFrames.get(0)).contains("\"type\":\"message.ack\"", "\"duplicate\":false");
        assertThat(senderFrames.get(1)).contains("\"type\":\"message.created\"");
        assertThat(peerFrames).singleElement().asString().contains("\"type\":\"message.created\"");

        senderFrames.clear();
        peerFrames.clear();
        clock.advanceSeconds(1);
        handler.handleTextMessage(sender, new TextMessage(send));

        assertThat(repository.saveCalls).isEqualTo(2);
        assertThat(senderFrames).singleElement().asString()
            .contains("\"type\":\"message.ack\"", "\"duplicate\":true");
        assertThat(peerFrames).isEmpty();
    }

    @Test
    void 세션_시작_전의_잘못된_첫_프레임은_치명적_오류다() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), clock);
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("not-json"));

        assertThat(frames).singleElement().asString()
            .contains("\"code\":\"INVALID_FRAME\"", "\"fatal\":true");
        assertThat(closed.get("sender").getCode()).isEqualTo(1008);
    }

    @Test
    void 연결별_1초_속도_제한을_적용한다() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), clock);
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(SESSION_START));
        frames.clear();
        handler.handleTextMessage(session, new TextMessage("""
            {"v":1,"type":"message.send","clientMessageId":"6ba7b811-9dad-41d1-80b4-00c04fd430c8","body":"첫 메시지"}
            """));
        frames.clear();

        handler.handleTextMessage(session, new TextMessage(sendFrame(EXAMPLE_MESSAGE_ID, "두 번째 메시지")));

        assertThat(frames).singleElement()
            .isEqualTo(example("error.rateLimited"));
        assertThat(closed).isEmpty();
    }

    @Test
    void 본문이_비었거나_200자를_넘으면_치명적이지_않은_INVALID_BODY다() throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        InMemoryRepository repository = new InMemoryRepository();
        ChatWebSocketHandler handler = handler(repository, clock);
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(SESSION_START));
        frames.clear();

        // when
        handler.handleTextMessage(session, new TextMessage(sendFrame(EXAMPLE_MESSAGE_ID, "   ")));
        handler.handleTextMessage(session, new TextMessage(sendFrame(EXAMPLE_MESSAGE_ID, "가".repeat(201))));

        // then
        assertThat(frames).containsExactly(example("error.invalidBody"), example("error.invalidBody"));
        assertThat(repository.saveCalls).isZero();
        assertThat(closed).isEmpty();
    }

    @Test
    void 이력을_읽지_못하면_session_ready_뒤_치명적이지_않은_CHAT_UNAVAILABLE을_보내고_1011로_닫는다() throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        ChatWebSocketHandler handler = handler(new UnavailableRepository(), clock);
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);

        // when
        handler.handleTextMessage(session, new TextMessage(SESSION_START));

        // then
        assertThat(frames).hasSize(2);
        assertThat(type(frames.get(0))).isEqualTo("session.ready");
        assertThat(ChatContractExamples.fieldNames(ChatContractExamples.parse(frames.get(0))))
            .containsExactlyElementsOf(ChatContractExamples.fieldNames(ChatContractExamples.serverFrame("session.ready")));
        assertThat(frames.get(1)).isEqualTo(example("error.unavailable"));
        assertThat(closed.get("sender")).isEqualTo(CloseStatus.SERVER_ERROR);
    }

    @Test
    void 보내기_중_저장소에_닿지_못하면_치명적이지_않은_CHAT_UNAVAILABLE이다() throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        UnavailableRepository repository = new UnavailableRepository();
        repository.historyAvailable = true;
        ChatWebSocketHandler handler = handler(repository, clock);
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(SESSION_START));
        frames.clear();

        // when
        handler.handleTextMessage(session, new TextMessage(sendFrame(EXAMPLE_MESSAGE_ID, "지금 자리 여유 있어요")));

        // then
        JsonNode error = ChatContractExamples.parse(frames.getFirst());
        assertThat(error.get("code").stringValue()).isEqualTo("CHAT_UNAVAILABLE");
        assertThat(error.get("requestId").stringValue()).isEqualTo(EXAMPLE_MESSAGE_ID);
        assertThat(error.get("retryAfterMs").isNull()).isTrue();
        assertThat(error.get("fatal").booleanValue()).isFalse();
        assertThat(closed).isEmpty();
    }

    @Test
    void 이력은_오래된_순으로_한_프레임에_10개까지_보내고_끝을_알린다() throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        InMemoryRepository repository = new InMemoryRepository();
        for (int index = 0; index < 23; index++) {
            repository.save(new ChatMessage(
                "68db%016x".formatted(index), "204000057", "id-" + index, "author", "졸린 범계역",
                "메시지 " + index, Instant.parse("2026-09-29T13:00:00Z").plusSeconds(index)
            ));
        }
        ChatWebSocketHandler handler = handler(repository, clock);
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);

        // when
        handler.handleTextMessage(session, new TextMessage(SESSION_START));

        // then
        assertThat(frames).extracting(this::type)
            .containsExactly("session.ready", "history.batch", "history.batch", "history.batch", "history.end");
        List<String> bodies = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (String frame : frames.subList(1, 4)) {
            JsonNode messages = ChatContractExamples.parse(frame).get("messages");
            sizes.add(messages.size());
            messages.forEach(message -> bodies.add(message.get("body").stringValue()));
        }
        assertThat(sizes).containsExactly(10, 10, 3);
        assertThat(bodies).containsExactlyElementsOf(
            IntStream.range(0, 23).mapToObj(index -> "메시지 " + index).toList()
        );
        assertThat(ChatContractExamples.fieldNames(ChatContractExamples.parse(frames.get(1)).get("messages").get(0)))
            .containsExactlyElementsOf(ChatContractExamples.fieldNames(
                ChatContractExamples.serverFrame("history.batch").get("messages").get(0)
            ));
    }

    @Test
    void 같은_ID로_다른_내용을_보내면_치명적이지_않은_ID_CONFLICT다() throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), clock);
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(SESSION_START));
        handler.handleTextMessage(session, new TextMessage(sendFrame(EXAMPLE_MESSAGE_ID, "처음 내용")));
        clock.advanceSeconds(1);
        frames.clear();

        // when
        handler.handleTextMessage(session, new TextMessage(sendFrame(EXAMPLE_MESSAGE_ID, "다른 내용")));

        // then
        JsonNode error = ChatContractExamples.parse(frames.getFirst());
        assertThat(frames).hasSize(1);
        assertThat(error.get("code").stringValue()).isEqualTo("ID_CONFLICT");
        assertThat(error.get("requestId").stringValue()).isEqualTo(EXAMPLE_MESSAGE_ID);
        assertThat(error.get("fatal").booleanValue()).isFalse();
        assertThat(closed).isEmpty();
    }

    @Test
    void 세션_시작_전의_message_send는_그_ID를_실은_HELLO_REQUIRED로_닫는다() throws Exception {
        // given
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), new MutableClock(Instant.EPOCH));
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);

        // when
        handler.handleTextMessage(session, new TextMessage(sendFrame(EXAMPLE_MESSAGE_ID, "안녕")));

        // then
        JsonNode error = ChatContractExamples.parse(frames.getFirst());
        assertThat(error.get("code").stringValue()).isEqualTo("HELLO_REQUIRED");
        assertThat(error.get("requestId").stringValue()).isEqualTo(EXAMPLE_MESSAGE_ID);
        assertThat(error.get("fatal").booleanValue()).isTrue();
        assertThat(closed.get("sender")).isEqualTo(CloseStatus.POLICY_VIOLATION.withReason("HELLO_REQUIRED"));
    }

    @Test
    void 버전이_1이_아니면_UNSUPPORTED_PROTOCOL로_닫는다() throws Exception {
        // given
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), new MutableClock(Instant.EPOCH));
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(SESSION_START));
        frames.clear();

        // when
        handler.handleTextMessage(session, new TextMessage("""
            {"v":2,"type":"message.send","clientMessageId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","text":"안녕"}
            """));

        // then
        JsonNode error = ChatContractExamples.parse(frames.getFirst());
        assertThat(error.get("code").stringValue()).isEqualTo("UNSUPPORTED_PROTOCOL");
        assertThat(error.get("requestId").stringValue()).isEqualTo(EXAMPLE_MESSAGE_ID);
        assertThat(error.get("fatal").booleanValue()).isTrue();
        assertThat(closed.get("sender")).isEqualTo(CloseStatus.POLICY_VIOLATION.withReason("UNSUPPORTED_PROTOCOL"));
    }

    @Test
    void 이미_시작한_세션의_session_start와_잘못된_프레임은_치명적이지_않은_INVALID_FRAME이다() throws Exception {
        // given
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), new MutableClock(Instant.EPOCH));
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(SESSION_START));
        frames.clear();

        // when
        handler.handleTextMessage(session, new TextMessage(SESSION_START));
        handler.handleTextMessage(session, new TextMessage("not-json"));

        // then
        assertThat(frames).hasSize(2).allSatisfy(frame -> {
            JsonNode error = ChatContractExamples.parse(frame);
            assertThat(error.get("code").stringValue()).isEqualTo("INVALID_FRAME");
            assertThat(error.get("requestId").isNull()).isTrue();
            assertThat(error.get("fatal").booleanValue()).isFalse();
        });
        assertThat(closed).isEmpty();
    }

    @Test
    void 프레임이_1024바이트를_넘으면_치명적_INVALID_FRAME이다() throws Exception {
        // given
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), new MutableClock(Instant.EPOCH));
        List<String> frames = new ArrayList<>();
        WebSocketSession session = session("sender", "204000057", frames);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(SESSION_START));
        frames.clear();

        // when
        handler.handleTextMessage(session, new TextMessage(sendFrame(EXAMPLE_MESSAGE_ID, "가".repeat(340))));

        // then
        JsonNode error = ChatContractExamples.parse(frames.getFirst());
        assertThat(error.get("code").stringValue()).isEqualTo("INVALID_FRAME");
        assertThat(error.get("requestId").isNull()).isTrue();
        assertThat(error.get("fatal").booleanValue()).isTrue();
        assertThat(closed.get("sender").getCode()).isEqualTo(1008);
    }

    @Test
    void 지원하지_않는_노선은_1003으로_닫는다() throws Exception {
        // given
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), new MutableClock(Instant.EPOCH));
        WebSocketSession session = session("sender", "200000001", new ArrayList<>());

        // when
        handler.afterConnectionEstablished(session);

        // then
        assertThat(closed.get("sender").getCode()).isEqualTo(1003);
    }

    @Test
    void 시작하지_않고_5초가_지나면_HELLO_REQUIRED로_닫고_70초_비활성은_4500으로_닫는다() throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), clock);
        List<String> silentFrames = new ArrayList<>();
        WebSocketSession silent = session("silent", "204000057", silentFrames);
        WebSocketSession idle = session("idle", "204000057", new ArrayList<>());
        handler.afterConnectionEstablished(silent);
        handler.afterConnectionEstablished(idle);
        handler.handleTextMessage(idle, new TextMessage(SESSION_START));

        // when
        clock.advanceSeconds(5);
        handler.heartbeat();
        clock.advanceSeconds(65);
        handler.heartbeat();

        // then
        assertThat(silentFrames).containsExactly(example("error.helloRequired"));
        assertThat(closed.get("silent").getCode()).isEqualTo(1008);
        assertThat(closed.get("idle").getCode()).isEqualTo(4500);
    }

    @Test
    void 입장_중에_저장된_메시지는_session_ready_앞에_끼어들지_않고_이력으로_온다() throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), clock);
        List<String> senderFrames = new ArrayList<>();
        List<String> joiningFrames = new ArrayList<>();
        WebSocketSession sender = session("sender", "204000057", senderFrames);
        WebSocketSession joining = session("joining", "204000057", joiningFrames);
        handler.afterConnectionEstablished(sender);
        handler.afterConnectionEstablished(joining);
        handler.handleTextMessage(sender, new TextMessage(SESSION_START));
        senderFrames.clear();
        AtomicBoolean armed = new AtomicBoolean(true);
        given(joining.isOpen()).willAnswer(invocation -> {
            if (armed.getAndSet(false)) {
                handler.handleTextMessage(sender, new TextMessage(sendFrame(EXAMPLE_MESSAGE_ID, "막 보낸 메시지")));
            }
            return true;
        });

        // when
        handler.handleTextMessage(joining, new TextMessage(OTHER_SESSION_START));

        // then
        assertThat(armed).isFalse();
        assertThat(senderFrames).extracting(this::type).containsExactly("message.ack", "message.created");
        assertThat(joiningFrames).extracting(this::type)
            .containsExactly("session.ready", "history.batch", "history.end");
        assertThat(ChatContractExamples.parse(joiningFrames.get(1)).get("messages").get(0).get("body").stringValue())
            .isEqualTo("막 보낸 메시지");
    }

    @Test
    void ping을_보내지_못한_세션은_1011로_닫고_같은_틱의_다른_세션도_검사한다() throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), clock);
        WebSocketSession broken = session("broken", "204000057", new ArrayList<>(), message -> {
            if (message instanceof PingMessage) {
                throw new IllegalStateException("session is closing");
            }
        });
        WebSocketSession healthy = session("healthy", "204000057", new ArrayList<>());
        List<String> silentFrames = new ArrayList<>();
        WebSocketSession silent = session("silent", "204000057", silentFrames);
        handler.afterConnectionEstablished(broken);
        handler.afterConnectionEstablished(healthy);
        handler.handleTextMessage(broken, new TextMessage(SESSION_START));
        handler.handleTextMessage(healthy, new TextMessage(OTHER_SESSION_START));
        clock.advanceSeconds(20);
        handler.afterConnectionEstablished(silent);
        clock.advanceSeconds(5);

        // when
        assertThatCode(handler::heartbeat).doesNotThrowAnyException();

        // then
        assertThat(closed.get("broken")).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(ChatContractExamples.parse(silentFrames.getFirst()).get("code").stringValue())
            .isEqualTo("HELLO_REQUIRED");
        assertThat(closed.get("silent").getCode()).isEqualTo(1008);
        assertThat(closed).doesNotContainKey("healthy");
        verify(healthy).sendMessage(any(PingMessage.class));
    }

    @Test
    void 보내기가_시간_초과된_세션만_1011로_닫고_방송은_나머지_세션에_이어_간다() throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), clock);
        List<String> senderFrames = new ArrayList<>();
        List<String> peerFrames = new ArrayList<>();
        WebSocketSession sender = session("sender", "204000057", senderFrames);
        WebSocketSession slow = session("slow", "204000057", new ArrayList<>(), message -> {
            if (message instanceof TextMessage text && text.getPayload().contains("\"type\":\"message.created\"")) {
                throw new SocketTimeoutException("blocking send timed out");
            }
        });
        WebSocketSession peer = session("peer", "204000057", peerFrames);
        for (WebSocketSession session : List.of(sender, slow, peer)) {
            handler.afterConnectionEstablished(session);
            handler.handleTextMessage(session, new TextMessage(SESSION_START));
        }
        senderFrames.clear();
        peerFrames.clear();

        // when
        handler.handleTextMessage(sender, new TextMessage(sendFrame(EXAMPLE_MESSAGE_ID, "지금 자리 여유 있어요")));

        // then
        assertThat(senderFrames).extracting(this::type).containsExactly("message.ack", "message.created");
        assertThat(peerFrames).extracting(this::type).containsExactly("message.created");
        assertThat(closed).containsOnlyKeys("slow");
        assertThat(closed.get("slow")).isEqualTo(CloseStatus.SERVER_ERROR);
    }

    @Test
    void 연결하면_Tomcat의_blocking_send_제한을_5초로_둔다() throws Exception {
        // given
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), new MutableClock(Instant.EPOCH));
        Map<String, Object> userProperties = new HashMap<>();
        Session container = mock(Session.class);
        given(container.getUserProperties()).willReturn(userProperties);
        NativeWebSocketSession session = mock(NativeWebSocketSession.class);
        given(session.getId()).willReturn("sender");
        given(session.getUri()).willReturn(URI.create("ws://localhost/api/chat/rooms/204000057/stream"));
        given(session.getAttributes()).willReturn(new HashMap<>());
        given(session.getNativeSession()).willReturn(container);

        // when
        handler.afterConnectionEstablished(session);

        // then
        assertThat(userProperties).containsEntry(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY, 5_000L);
    }

    private String example(String name) {
        return ChatContractExamples.compact(ChatContractExamples.serverFrame(name));
    }

    private String type(String frame) {
        return ChatContractExamples.parse(frame).get("type").stringValue();
    }

    private String sendFrame(String clientMessageId, String body) {
        return """
            {"v":1,"type":"message.send","clientMessageId":"%s","body":"%s"}
            """.formatted(clientMessageId, body);
    }

    @Test
    void 요약_로그는_방_세션_보냄_거절_수만_남기고_본문과_ID는_남기지_않는다(CapturedOutput output) throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        ChatWebSocketHandler handler = handler(new InMemoryRepository(), clock);
        WebSocketSession sender = session("sender", "204000057", new ArrayList<>());
        WebSocketSession peer = session("peer", "204000057", new ArrayList<>());
        handler.afterConnectionEstablished(sender);
        handler.afterConnectionEstablished(peer);
        handler.handleTextMessage(sender, new TextMessage(SESSION_START));
        handler.handleTextMessage(peer, new TextMessage(OTHER_SESSION_START));
        handler.handleTextMessage(sender, new TextMessage(messageSend(EXAMPLE_MESSAGE_ID, "요약에 남으면 안 되는 본문")));
        handler.handleTextMessage(sender, new TextMessage(messageSend("6ba7b811-9dad-41d1-80b4-00c04fd430c8", "두 번째")));

        // when
        handler.summarize();
        handler.summarize();

        // then
        assertThat(output.getOut())
            .contains("채팅 1분 요약. 방=1 세션=2 보냄=1 거절=1 저장오류=0 연결초과=0 마지막저장오류=-")
            .contains("채팅 1분 요약. 방=1 세션=2 보냄=0 거절=0 저장오류=0 연결초과=0 마지막저장오류=-")
            .doesNotContain("요약에 남으면 안 되는 본문")
            .doesNotContain(EXAMPLE_MESSAGE_ID)
            .doesNotContain("0f8fad5b-d9cb-469f-a165-70867728950e");
    }

    @Test
    void 저장소_오류는_예외_이름만_요약에_남기고_할_일이_없으면_요약을_남기지_않는다(CapturedOutput output) throws Exception {
        // given
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
        ChatWebSocketHandler handler = handler(new UnavailableRepository(), clock);
        WebSocketSession session = session("sender", "204000057", new ArrayList<>());
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(SESSION_START));

        // when
        handler.summarize();
        handler.summarize();

        // then
        assertThat(output.getOut())
            .contains("채팅 1분 요약. 방=0 세션=0 보냄=0 거절=0 저장오류=1 연결초과=0 마지막저장오류=IllegalStateException")
            .doesNotContain("store is unreachable");
        assertThat(output.getOut().split("채팅 1분 요약", -1)).hasSize(2);
    }

    private String messageSend(String clientMessageId, String body) {
        return "{\"v\":1,\"type\":\"message.send\",\"clientMessageId\":\"" + clientMessageId
            + "\",\"body\":\"" + body + "\"}";
    }

    private ChatWebSocketHandler handler(ChatMessageRepository repository, Clock clock) {
        return new ChatWebSocketHandler(
            ChatTestRoutes.catalog(),
            new ChatIdentityService(),
            new ChatService(repository, clock),
            new ChatFrameCodec(JsonMapper.builder().findAndAddModules().build()),
            new ChatConnectionLimiter(100, 5),
            clock
        );
    }

    private WebSocketSession session(String id, String routeId, List<String> frames) throws Exception {
        return session(id, routeId, frames, message -> {
        });
    }

    private WebSocketSession session(String id, String routeId, List<String> frames, Outbound outbound)
        throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn(id);
        given(session.getUri()).willReturn(URI.create("ws://localhost/api/chat/rooms/" + routeId + "/stream"));
        given(session.isOpen()).willReturn(true);
        given(session.getAttributes()).willReturn(new HashMap<>(Map.of(
            ChatClientAddressInterceptor.CLIENT_ADDRESS_ATTRIBUTE, "203.0.113.10"
        )));
        doAnswer(invocation -> {
            WebSocketMessage<?> message = invocation.getArgument(0);
            outbound.accept(message);
            if (message instanceof TextMessage text) {
                frames.add(text.getPayload());
            }
            return null;
        }).when(session).sendMessage(any(WebSocketMessage.class));
        doAnswer(invocation -> {
            closed.put(id, invocation.getArgument(0));
            return null;
        }).when(session).close(any(CloseStatus.class));
        return session;
    }

    @FunctionalInterface
    private interface Outbound {
        void accept(WebSocketMessage<?> message) throws IOException;
    }

    private static final class InMemoryRepository implements ChatMessageRepository {
        private final Map<String, ChatMessage> stored = new LinkedHashMap<>();
        private int saveCalls;
        private int issuedIds;

        @Override
        public String nextId() {
            issuedIds++;
            return "%024x".formatted(issuedIds);
        }

        @Override
        public SaveResult save(ChatMessage message) {
            saveCalls++;
            ChatMessage existing = stored.putIfAbsent(message.roomId() + ":" + message.clientMessageId(), message);
            if (existing == null) {
                return new SaveResult(SaveResult.Status.CREATED, message);
            }
            if (existing.authorId().equals(message.authorId()) && existing.body().equals(message.body())) {
                return new SaveResult(SaveResult.Status.DUPLICATE, existing);
            }
            return new SaveResult(SaveResult.Status.CONFLICT, existing);
        }

        @Override
        public List<ChatMessage> findRecent(String roomId, final int limit) {
            return stored.values().stream()
                .filter(message -> message.roomId().equals(roomId))
                .limit(limit)
                .toList();
        }
    }

    private static final class UnavailableRepository implements ChatMessageRepository {
        private boolean historyAvailable;

        @Override
        public String nextId() {
            return "68db00000000000000000003";
        }

        @Override
        public SaveResult save(ChatMessage message) {
            throw new IllegalStateException("store is unreachable");
        }

        @Override
        public List<ChatMessage> findRecent(String roomId, final int limit) {
            if (historyAvailable) {
                return List.of();
            }
            throw new IllegalStateException("store is unreachable");
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(final long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
