package com.gustler.backend.api.chat.controller;

import com.gustler.backend.api.chat.application.ChatIdentity;
import com.gustler.backend.api.chat.application.ChatIdentityService;
import com.gustler.backend.api.chat.application.ChatMessageRepository;
import com.gustler.backend.api.chat.application.ChatRoom;
import com.gustler.backend.api.chat.application.ChatRoomCatalog;
import com.gustler.backend.api.chat.application.ChatService;
import com.gustler.backend.api.chat.controller.ChatFrameCodec.ClientFrame;
import com.gustler.backend.api.chat.controller.ChatFrameCodec.MessageSend;
import com.gustler.backend.api.chat.controller.ChatFrameCodec.SessionStart;
import com.gustler.backend.api.chat.controller.ChatFrameCodec.UnsupportedProtocol;
import com.gustler.backend.api.chat.domain.ChatMessage;
import com.gustler.backend.api.chat.dto.ChatMessageResponse;
import com.gustler.backend.api.chat.dto.ChatServerFrame;
import jakarta.websocket.Session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public final class ChatWebSocketHandler extends TextWebSocketHandler {

    public static final int MAX_FRAME_BYTES = 1_024;
    private static final int HISTORY_BATCH_SIZE = 10;
    private static final Duration START_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration INACTIVE_TIMEOUT = Duration.ofSeconds(70);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(25);
    private static final Duration SEND_INTERVAL = Duration.ofSeconds(1);
    private static final long RETRY_AFTER_MS = SEND_INTERVAL.toMillis();
    private static final Duration SEND_TIME_LIMIT = Duration.ofSeconds(5);
    private static final int SEND_BUFFER_LIMIT_BYTES = 64 * 1_024;
    private static final String TOMCAT_BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";
    private static final String STORE_UNAVAILABLE = "CHAT_UNAVAILABLE";
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatRoomCatalog rooms;
    private final ChatIdentityService identities;
    private final ChatService chatService;
    private final ChatFrameCodec codec;
    private final ChatConnectionLimiter connections;
    private final Clock clock;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicLong createdCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong storeErrorCount = new AtomicLong();
    private final AtomicLong overloadedCount = new AtomicLong();
    private final AtomicReference<String> lastStoreError = new AtomicReference<>("-");

    public ChatWebSocketHandler(
        ChatRoomCatalog rooms,
        ChatIdentityService identities,
        ChatService chatService,
        ChatFrameCodec codec,
        ChatConnectionLimiter connections,
        Clock clock
    ) {
        this.rooms = rooms;
        this.identities = identities;
        this.chatService = chatService;
        this.codec = codec;
        this.connections = connections;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        limitBlockingSend(session);
        Optional<ChatRoom> room = rooms.find(routeId(session));
        if (room.isEmpty()) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("unsupported route"));
            return;
        }
        if (!connections.tryAcquire(session.getId(), clientAddress(session))) {
            overloadedCount.incrementAndGet();
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        WebSocketSession guardedSession = new ConcurrentWebSocketSessionDecorator(
            session, (int) SEND_TIME_LIMIT.toMillis(), SEND_BUFFER_LIMIT_BYTES
        );
        sessions.put(session.getId(), new SessionState(guardedSession, session, room.get(), clock.instant()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionState state = sessions.get(session.getId());
        if (state == null) {
            return;
        }
        state.touch(clock.instant());
        if (message.getPayload().getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
            fail(state, null, "INVALID_FRAME", "프레임이 너무 큽니다.", null, true);
            return;
        }

        ClientFrame frame;
        try {
            frame = codec.decode(message.getPayload());
        } catch (RuntimeException exception) {
            fail(state, null, "INVALID_FRAME", "올바르지 않은 요청입니다.", null, !state.started());
            return;
        }
        switch (frame) {
            case UnsupportedProtocol unsupported ->
                fail(state, unsupported.requestId(), "UNSUPPORTED_PROTOCOL", "지원하지 않는 프로토콜입니다.", null, true);
            case SessionStart start -> start(state, start);
            case MessageSend send -> {
                if (state.started()) {
                    send(state, send);
                } else {
                    fail(state, send.clientMessageId(), "HELLO_REQUIRED", "먼저 세션을 시작해 주세요.", null, true);
                }
            }
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        SessionState state = sessions.get(session.getId());
        if (state != null) {
            state.touch(clock.instant());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        forget(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        forget(session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    public void heartbeat() {
        Instant now = clock.instant();
        for (SessionState state : new ArrayList<>(sessions.values())) {
            if (!state.started() && Duration.between(state.connectedAt(), now).compareTo(START_TIMEOUT) >= 0) {
                fail(state, null, "HELLO_REQUIRED", "세션 시작 시간이 지났습니다.", null, true);
                continue;
            }
            if (Duration.between(state.lastActivity(), now).compareTo(INACTIVE_TIMEOUT) >= 0) {
                close(state, CloseStatus.SESSION_NOT_RELIABLE.withReason("inactive"));
                continue;
            }
            if (state.shouldPing(now)) {
                try {
                    state.send(new PingMessage());
                } catch (IOException | RuntimeException exception) {
                    close(state, CloseStatus.SERVER_ERROR);
                }
            }
        }
    }

    public void summarize() {
        final long created = createdCount.getAndSet(0);
        final long rejected = rejectedCount.getAndSet(0);
        final long storeErrors = storeErrorCount.getAndSet(0);
        final long overloaded = overloadedCount.getAndSet(0);
        String storeError = lastStoreError.getAndSet("-");
        List<SessionState> active = new ArrayList<>(sessions.values());
        if (active.isEmpty() && created == 0 && rejected == 0 && storeErrors == 0 && overloaded == 0) {
            return;
        }
        final long rooms = active.stream().map(SessionState::routeId).distinct().count();
        log.info("채팅 1분 요약. 방={} 세션={} 보냄={} 거절={} 저장오류={} 연결초과={} 마지막저장오류={}",
            rooms, active.size(), created, rejected, storeErrors, overloaded, storeError);
    }

    private void start(SessionState state, SessionStart start) {
        if (state.started()) {
            fail(state, null, "INVALID_FRAME", "세션은 이미 시작되었습니다.", null, false);
            return;
        }
        ChatIdentity identity = identities.derive(state.room(), start.clientSessionId());
        sendFrame(state, new ChatServerFrame.SessionReady(
            1, "session.ready", identity.authorId(), identity.nickname(), ChatService.MAX_BODY_CODE_POINTS
        ));
        state.start(identity);
        try {
            List<ChatMessage> history = chatService.recent(state.routeId());
            for (int index = 0; index < history.size(); index += HISTORY_BATCH_SIZE) {
                List<ChatMessageResponse> batch = history
                    .subList(index, Math.min(index + HISTORY_BATCH_SIZE, history.size()))
                    .stream().map(ChatMessageResponse::from).toList();
                sendFrame(state, new ChatServerFrame.HistoryBatch(1, "history.batch", batch));
            }
            sendFrame(state, new ChatServerFrame.HistoryEnd(1, "history.end"));
        } catch (RuntimeException exception) {
            lastStoreError.set(exception.getClass().getSimpleName());
            fail(state, null, STORE_UNAVAILABLE, "채팅을 잠시 사용할 수 없습니다.", null, false);
            close(state, CloseStatus.SERVER_ERROR);
        }
    }

    private void send(SessionState state, MessageSend request) {
        String body = request.body().strip();
        if (body.isEmpty() || body.codePointCount(0, body.length()) > ChatService.MAX_BODY_CODE_POINTS) {
            fail(state, request.clientMessageId(), "INVALID_BODY", "메시지는 1자 이상 200자 이하여야 합니다.", null, false);
            return;
        }
        if (!state.canSend(clock.instant())) {
            fail(state, request.clientMessageId(), "RATE_LIMITED", "잠시 후 다시 보내 주세요.", RETRY_AFTER_MS, false);
            return;
        }

        try {
            ChatMessageRepository.SaveResult result = chatService.send(
                state.routeId(), request.clientMessageId(), state.identity(), body
            );
            if (result.status() == ChatMessageRepository.SaveResult.Status.CONFLICT) {
                fail(state, request.clientMessageId(), "ID_CONFLICT", "이미 사용된 메시지 ID입니다.", null, false);
                return;
            }
            ChatMessageResponse response = ChatMessageResponse.from(result.message());
            sendFrame(state, new ChatServerFrame.MessageAck(
                1,
                "message.ack",
                request.clientMessageId(),
                result.status() == ChatMessageRepository.SaveResult.Status.DUPLICATE,
                response
            ));
            if (result.status() == ChatMessageRepository.SaveResult.Status.CREATED) {
                createdCount.incrementAndGet();
                broadcast(state.routeId(), new ChatServerFrame.MessageCreated(1, "message.created", response));
            }
        } catch (RuntimeException exception) {
            lastStoreError.set(exception.getClass().getSimpleName());
            fail(state, request.clientMessageId(), STORE_UNAVAILABLE, "채팅을 잠시 사용할 수 없습니다.", null, false);
        }
    }

    private void broadcast(String routeId, ChatServerFrame frame) {
        for (SessionState target : sessions.values()) {
            if (target.started() && target.routeId().equals(routeId)) {
                sendFrame(target, frame);
            }
        }
    }

    private void fail(
        SessionState state,
        String requestId,
        String code,
        String message,
        Long retryAfterMs,
        final boolean fatal
    ) {
        if (STORE_UNAVAILABLE.equals(code)) {
            storeErrorCount.incrementAndGet();
        } else {
            rejectedCount.incrementAndGet();
        }
        sendFrame(state, new ChatServerFrame.Error(
            1, "error", requestId, code, message, retryAfterMs, fatal
        ));
        if (fatal) {
            close(state, CloseStatus.POLICY_VIOLATION.withReason(code));
        }
    }

    private void sendFrame(SessionState state, ChatServerFrame frame) {
        try {
            state.send(new TextMessage(codec.encode(frame)));
        } catch (IOException | RuntimeException exception) {
            close(state, CloseStatus.SERVER_ERROR);
        }
    }

    private void close(SessionState state, CloseStatus status) {
        forget(state.session());
        try {
            if (state.rawSession().isOpen()) {
                state.rawSession().close(status);
            }
        } catch (IOException ignored) {
        }
    }

    private void limitBlockingSend(WebSocketSession session) {
        if (session instanceof NativeWebSocketSession nativeSession
            && nativeSession.getNativeSession() instanceof Session container) {
            container.getUserProperties().put(TOMCAT_BLOCKING_SEND_TIMEOUT, SEND_TIME_LIMIT.toMillis());
        }
    }

    private void forget(WebSocketSession session) {
        sessions.remove(session.getId());
        connections.release(session.getId());
    }

    private String clientAddress(WebSocketSession session) {
        if (session.getAttributes().get(ChatClientAddressInterceptor.CLIENT_ADDRESS_ATTRIBUTE) instanceof String address) {
            return address;
        }
        return ChatClientAddressInterceptor.hostAddress(session.getRemoteAddress());
    }

    private String routeId(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        String prefix = "/api/chat/rooms/";
        String suffix = "/stream";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return "";
        }
        return path.substring(prefix.length(), path.length() - suffix.length());
    }

    private static final class SessionState {
        private final WebSocketSession session;
        private final WebSocketSession rawSession;
        private final ChatRoom room;
        private final Instant connectedAt;
        private volatile Instant lastActivity;
        private volatile Instant lastPingAt;
        private volatile Instant lastSentAt;
        private volatile ChatIdentity identity;

        private SessionState(WebSocketSession session, WebSocketSession rawSession, ChatRoom room, Instant connectedAt) {
            this.session = session;
            this.rawSession = rawSession;
            this.room = room;
            this.connectedAt = connectedAt;
            this.lastActivity = connectedAt;
            this.lastPingAt = connectedAt;
        }

        private void send(WebSocketMessage<?> message) throws IOException {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        }

        private synchronized boolean canSend(Instant now) {
            if (lastSentAt != null && Duration.between(lastSentAt, now).compareTo(SEND_INTERVAL) < 0) {
                return false;
            }
            lastSentAt = now;
            return true;
        }

        private synchronized boolean shouldPing(Instant now) {
            if (Duration.between(lastPingAt, now).compareTo(PING_INTERVAL) < 0) {
                return false;
            }
            lastPingAt = now;
            return true;
        }

        private void start(ChatIdentity identity) {
            this.identity = identity;
        }

        private void touch(Instant instant) {
            lastActivity = instant;
        }

        private boolean started() {
            return identity != null;
        }

        private WebSocketSession session() {
            return session;
        }

        private WebSocketSession rawSession() {
            return rawSession;
        }

        private ChatRoom room() {
            return room;
        }

        private String routeId() {
            return room.routeId();
        }

        private Instant connectedAt() {
            return connectedAt;
        }

        private Instant lastActivity() {
            return lastActivity;
        }

        private ChatIdentity identity() {
            return identity;
        }
    }
}
