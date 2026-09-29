package com.gustler.backend.api.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.gustler.backend.api.chat.application.ChatIdentityService;
import com.gustler.backend.api.chat.application.ChatMessageRepository;
import com.gustler.backend.api.chat.application.ChatRoomCatalog;
import com.gustler.backend.api.chat.application.ChatService;
import com.gustler.backend.api.chat.domain.ChatMessage;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class ChatConnectionLimitTest {

    private static final String ROUTE_3330 = "204000057";

    private final Map<String, CloseStatus> closed = new ConcurrentHashMap<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-29T13:21:34.127Z"));
    private final ChatWebSocketHandler handler = new ChatWebSocketHandler(
        new ChatRoomCatalog(),
        new ChatIdentityService(),
        new ChatService(new EmptyRepository(), clock),
        new ChatFrameCodec(JsonMapper.builder().build()),
        new ChatConnectionLimiter(),
        clock
    );

    @Test
    void 전체_연결이_100개면_101번째를_1013으로_닫는다() throws Exception {
        // given
        for (int index = 0; index < 100; index++) {
            handler.afterConnectionEstablished(session("s" + index, ROUTE_3330, "198.51.100." + index / 5));
        }

        // when
        handler.afterConnectionEstablished(session("s100", ROUTE_3330, "192.0.2.1"));

        // then
        assertThat(closed).containsOnlyKeys("s100");
        assertThat(closed.get("s100")).isEqualTo(CloseStatus.SERVICE_OVERLOAD);
        assertThat(closed.get("s100").getCode()).isEqualTo(1013);
    }

    @Test
    void 같은_IP의_6번째_연결은_1013으로_닫고_다른_IP는_받는다() throws Exception {
        // given
        for (int index = 0; index < 5; index++) {
            handler.afterConnectionEstablished(session("a" + index, ROUTE_3330, "198.51.100.7"));
        }

        // when
        handler.afterConnectionEstablished(session("a5", ROUTE_3330, "198.51.100.7"));
        handler.afterConnectionEstablished(session("b0", ROUTE_3330, "198.51.100.8"));

        // then
        assertThat(closed).containsOnlyKeys("a5");
        assertThat(closed.get("a5").getCode()).isEqualTo(1013);
    }

    @Test
    void 연결이_닫히면_같은_IP가_다시_들어온다() throws Exception {
        // given
        List<WebSocketSession> sessions = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            WebSocketSession session = session("a" + index, ROUTE_3330, "198.51.100.7");
            sessions.add(session);
            handler.afterConnectionEstablished(session);
        }

        // when
        handler.afterConnectionClosed(sessions.getFirst(), CloseStatus.NORMAL);
        handler.afterConnectionEstablished(session("a5", ROUTE_3330, "198.51.100.7"));

        // then
        assertThat(closed).isEmpty();
    }

    @Test
    void 전체_상한에서_하나가_닫히면_다시_받는다() throws Exception {
        // given
        List<WebSocketSession> sessions = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            WebSocketSession session = session("s" + index, ROUTE_3330, "198.51.100." + index / 5);
            sessions.add(session);
            handler.afterConnectionEstablished(session);
        }

        // when
        handler.afterConnectionClosed(sessions.get(42), CloseStatus.GOING_AWAY);
        handler.afterConnectionEstablished(session("s100", ROUTE_3330, "192.0.2.1"));
        handler.afterConnectionEstablished(session("s101", ROUTE_3330, "192.0.2.2"));

        // then
        assertThat(closed).containsOnlyKeys("s101");
        assertThat(closed.get("s101").getCode()).isEqualTo(1013);
    }

    @Test
    void 한_세션이_여러_경로로_끝나도_IP_카운트는_한_번만_줄어든다() throws Exception {
        // given
        List<WebSocketSession> sessions = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            WebSocketSession session = session("a" + index, ROUTE_3330, "198.51.100.7");
            sessions.add(session);
            handler.afterConnectionEstablished(session);
        }
        WebSocketSession ending = sessions.getFirst();

        // when
        handler.handleTextMessage(ending, new TextMessage("not-json"));
        handler.handleTransportError(ending, new IllegalStateException("broken pipe"));
        handler.afterConnectionClosed(ending, CloseStatus.POLICY_VIOLATION);
        handler.afterConnectionEstablished(session("a5", ROUTE_3330, "198.51.100.7"));
        handler.afterConnectionEstablished(session("a6", ROUTE_3330, "198.51.100.7"));

        // then
        assertThat(closed).containsOnlyKeys("a0", "a6");
        assertThat(closed.get("a0").getCode()).isEqualTo(1008);
        assertThat(closed.get("a6").getCode()).isEqualTo(1013);
    }

    @Test
    void 하트비트로_끝난_세션도_IP_카운트를_한_번만_줄인다() throws Exception {
        // given
        WebSocketSession silent = session("a0", ROUTE_3330, "198.51.100.7");
        handler.afterConnectionEstablished(silent);
        clock.advanceSeconds(1);
        for (int index = 1; index < 5; index++) {
            handler.afterConnectionEstablished(session("a" + index, ROUTE_3330, "198.51.100.7"));
        }

        // when
        clock.advanceSeconds(4);
        handler.heartbeat();
        handler.afterConnectionClosed(silent, CloseStatus.POLICY_VIOLATION);
        handler.afterConnectionEstablished(session("a5", ROUTE_3330, "198.51.100.7"));
        handler.afterConnectionEstablished(session("a6", ROUTE_3330, "198.51.100.7"));

        // then
        assertThat(closed).containsOnlyKeys("a0", "a6");
        assertThat(closed.get("a0").getCode()).isEqualTo(1008);
        assertThat(closed.get("a6").getCode()).isEqualTo(1013);
    }

    @Test
    void 비활성으로_끝난_세션도_IP_카운트를_한_번만_줄인다() throws Exception {
        // given
        WebSocketSession idle = session("a0", ROUTE_3330, "198.51.100.7");
        handler.afterConnectionEstablished(idle);
        handler.handleTextMessage(idle, new TextMessage("""
            {"v":1,"type":"session.start","clientSessionId":"0f8fad5b-d9cb-469f-a165-70867728950e"}
            """));
        clock.advanceSeconds(69);
        for (int index = 1; index < 5; index++) {
            WebSocketSession active = session("a" + index, ROUTE_3330, "198.51.100.7");
            handler.afterConnectionEstablished(active);
            handler.handleTextMessage(active, new TextMessage("""
                {"v":1,"type":"session.start","clientSessionId":"6ba7b810-9dad-41d1-80b4-00c04fd430c8"}
                """));
        }

        // when
        clock.advanceSeconds(1);
        handler.heartbeat();
        handler.afterConnectionClosed(idle, CloseStatus.SESSION_NOT_RELIABLE);
        handler.afterConnectionEstablished(session("a5", ROUTE_3330, "198.51.100.7"));
        handler.afterConnectionEstablished(session("a6", ROUTE_3330, "198.51.100.7"));

        // then
        assertThat(closed).containsOnlyKeys("a0", "a6");
        assertThat(closed.get("a0").getCode()).isEqualTo(4500);
        assertThat(closed.get("a6").getCode()).isEqualTo(1013);
    }

    @Test
    void 지원하지_않는_노선은_상한을_보기_전에_1003으로_닫고_자리를_차지하지_않는다() throws Exception {
        // given
        for (int index = 0; index < 5; index++) {
            handler.afterConnectionEstablished(session("x" + index, "200000001", "198.51.100.7"));
        }

        // when
        handler.afterConnectionEstablished(session("a0", ROUTE_3330, "198.51.100.7"));

        // then
        assertThat(closed).containsOnlyKeys("x0", "x1", "x2", "x3", "x4");
        assertThat(closed.values()).allSatisfy(status -> assertThat(status.getCode()).isEqualTo(1003));
    }

    @Test
    void 동시에_연결해도_전체와_IP당_상한을_넘지_않는다() throws Exception {
        // given
        List<WebSocketSession> sessions = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            sessions.add(session("s" + index, ROUTE_3330, "198.51.100." + index % 20));
        }
        for (int index = 0; index < 30; index++) {
            sessions.add(session("same" + index, ROUTE_3330, "192.0.2.1"));
        }
        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();

        // when
        try {
            for (WebSocketSession session : sessions) {
                futures.add(executor.submit(() -> {
                    ready.await();
                    handler.afterConnectionEstablished(session);
                    return null;
                }));
            }
            ready.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        // then
        List<String> admitted = sessions.stream()
            .map(WebSocketSession::getId)
            .filter(id -> !closed.containsKey(id))
            .toList();
        assertThat(admitted).hasSize(100);
        assertThat(admitted.stream().filter(id -> id.startsWith("same"))).hasSizeLessThanOrEqualTo(5);
        Map<String, Integer> perAddress = new HashMap<>();
        for (WebSocketSession session : sessions) {
            if (!closed.containsKey(session.getId())) {
                perAddress.merge(
                    (String) session.getAttributes().get(ChatClientAddressInterceptor.CLIENT_ADDRESS_ATTRIBUTE), 1, Integer::sum
                );
            }
        }
        assertThat(perAddress.values()).allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(5));
        assertThat(closed.values()).allSatisfy(status -> assertThat(status.getCode()).isEqualTo(1013));
    }

    private WebSocketSession session(String id, String routeId, String clientAddress) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn(id);
        given(session.getUri()).willReturn(URI.create("ws://chat.test/api/chat/rooms/" + routeId + "/stream"));
        given(session.isOpen()).willReturn(true);
        given(session.getAttributes()).willReturn(new HashMap<>(Map.of(
            ChatClientAddressInterceptor.CLIENT_ADDRESS_ATTRIBUTE, clientAddress
        )));
        doAnswer(invocation -> null).when(session).sendMessage(any(WebSocketMessage.class));
        doAnswer(invocation -> {
            closed.putIfAbsent(id, invocation.getArgument(0));
            return null;
        }).when(session).close(any(CloseStatus.class));
        return session;
    }

    private static final class EmptyRepository implements ChatMessageRepository {

        @Override
        public String nextId() {
            return "68db00000000000000000003";
        }

        @Override
        public SaveResult save(ChatMessage message) {
            return new SaveResult(SaveResult.Status.CREATED, message);
        }

        @Override
        public List<ChatMessage> findRecent(String roomId, final int limit) {
            return List.of();
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
