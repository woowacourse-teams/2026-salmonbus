package com.gustler.backend.api.chat.controller;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

final class ChatTestClient extends TextWebSocketHandler {

    static final String ALLOWED_ORIGIN = "https://www.salmonbus.com";

    private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private WebSocketSession session;

    private ChatTestClient() {
    }

    static ChatTestClient connect(final int port, String routeId, String origin) throws Exception {
        ChatTestClient client = new ChatTestClient();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        URI uri = URI.create("ws://127.0.0.1:" + port + "/api/chat/rooms/" + routeId + "/stream");
        client.session = new StandardWebSocketClient().execute(client, headers, uri).get(5, SECONDS);
        return client;
    }

    void send(String payload) throws IOException {
        session.sendMessage(new TextMessage(payload));
    }

    String next(Duration timeout) throws InterruptedException {
        String frame = frames.poll(timeout.toMillis(), MILLISECONDS);
        if (frame == null) {
            throw new AssertionError("no frame within " + timeout);
        }
        return frame;
    }

    String poll(Duration timeout) throws InterruptedException {
        return frames.poll(timeout.toMillis(), MILLISECONDS);
    }

    CloseStatus closeStatus(Duration timeout) throws Exception {
        return closed.get(timeout.toMillis(), MILLISECONDS);
    }

    void close() throws IOException {
        if (session.isOpen()) {
            session.close();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        frames.add(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closed.complete(status);
    }
}
