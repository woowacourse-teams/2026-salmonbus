package com.gustler.backend.api.push.delivery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class FakeApnsServer implements AutoCloseable {
    record Received(String token, String authorization, String topic, String pushType, String body) {
    }

    private record Answer(int status, String reason) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Received> received = new ArrayList<>();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private volatile Answer defaultAnswer = new Answer(200, null);
    private volatile Runnable beforeResponse = () -> {
    };

    FakeApnsServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/3/device", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    URI baseUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    void answerAll(int status, String reason) {
        defaultAnswer = new Answer(status, reason);
    }

    void answer(String token, int status, String reason) {
        answers.put(token, new Answer(status, reason));
    }

    void beforeResponse(Runnable hook) {
        beforeResponse = hook == null ? () -> {
        } : hook;
    }

    List<Received> requests() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    List<Received> requestsFor(String token) {
        synchronized (received) {
            return received.stream().filter(request -> request.token().equals(token)).toList();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String token = path.substring(path.lastIndexOf('/') + 1);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received request = new Received(
            token,
            exchange.getRequestHeaders().getFirst("authorization"),
            exchange.getRequestHeaders().getFirst("apns-topic"),
            exchange.getRequestHeaders().getFirst("apns-push-type"),
            body
        );
        synchronized (received) {
            received.add(request);
        }
        beforeResponse.run();
        Answer answer = answers.getOrDefault(token, defaultAnswer);
        String responseBody = answer.reason() == null ? "" : "{\"reason\":\"" + answer.reason() + "\"}";
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(answer.status(), -1);
        } else {
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
