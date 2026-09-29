package com.gustler.backend.api.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.PostgresTestContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
@DirtiesContext
class ChatMongoUnavailableIntegrationTest {

    private static final int CLOSED_PORT = closedPort();
    private static final String SESSION_START = """
        {"v":1,"type":"session.start","clientSessionId":"0f8fad5b-d9cb-469f-a165-70867728950e"}
        """;

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void chatProperties(DynamicPropertyRegistry registry) {
        registry.add("chat.enabled", () -> "true");
        registry.add("chat.mongodb-uri", () -> "mongodb://127.0.0.1:" + CLOSED_PORT);
    }

    @Test
    void Mongo가_없어도_readyz와_health와_채팅_켜짐_확인이_200이다() throws Exception {
        // when
        HttpResponse<String> readyz = get("/readyz");
        HttpResponse<String> health = get("/actuator/health");
        HttpResponse<String> availability = get("/api/chat/rooms/204000057");

        // then
        assertThat(readyz.statusCode()).isEqualTo(200);
        assertThat(readyz.body()).contains("\"status\":\"UP\"");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(availability.statusCode()).isEqualTo(200);
        assertThat(availability.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(ChatContractExamples.parse(availability.body())).isEqualTo(ChatContractExamples.availability());
    }

    @Test
    void Mongo가_없으면_session_ready_뒤_치명적이지_않은_CHAT_UNAVAILABLE을_2초_남짓에_보내고_1011로_닫는다() throws Exception {
        // given
        ChatTestClient client = ChatTestClient.connect(port, "204000057", ChatTestClient.ALLOWED_ORIGIN);

        // when
        final long startedAt = System.nanoTime();
        client.send(SESSION_START);
        String ready = client.next(Duration.ofSeconds(5));
        String error = client.next(Duration.ofSeconds(5));
        final long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        CloseStatus closeStatus = client.closeStatus(Duration.ofSeconds(5));

        // then
        assertThat(ChatContractExamples.parse(ready).get("type").stringValue()).isEqualTo("session.ready");
        assertThat(error).isEqualTo(ChatContractExamples.compact(ChatContractExamples.serverFrame("error.unavailable")));
        assertThat(elapsedMillis).isLessThan(3_000L);
        assertThat(closeStatus.getCode()).isEqualTo(1011);
    }

    @Test
    void 허용하지_않은_Origin은_핸드셰이크를_거절한다() {
        assertThatThrownBy(() -> ChatTestClient.connect(port, "204000057", "http://localhost:3000"))
            .isInstanceOf(ExecutionException.class)
            .rootCause()
            .hasMessageContaining("[403]");
    }

    @Test
    void 켜져_있어도_지원하지_않는_노선의_켜짐_확인은_no_store_404다() throws Exception {
        // when
        HttpResponse<String> availability = get("/api/chat/rooms/200000001");

        // then
        assertThat(availability.statusCode()).isEqualTo(404);
        assertThat(availability.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test
    void 세션을_시작하지_않으면_5초_뒤_HELLO_REQUIRED를_받고_1008로_닫힌다() throws Exception {
        // given
        ChatTestClient client = ChatTestClient.connect(port, "204000057", ChatTestClient.ALLOWED_ORIGIN);
        final long connectedAt = System.nanoTime();

        // when
        String error = client.next(Duration.ofSeconds(10));
        final long elapsedMillis = Duration.ofNanos(System.nanoTime() - connectedAt).toMillis();
        CloseStatus closeStatus = client.closeStatus(Duration.ofSeconds(5));

        // then
        assertThat(error).isEqualTo(ChatContractExamples.compact(ChatContractExamples.serverFrame("error.helloRequired")));
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(4_000L);
        assertThat(closeStatus).isEqualTo(CloseStatus.POLICY_VIOLATION.withReason("HELLO_REQUIRED"));
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
