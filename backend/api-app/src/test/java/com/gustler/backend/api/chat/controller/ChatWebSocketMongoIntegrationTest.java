package com.gustler.backend.api.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.chat.application.ChatMessageRepository;
import com.gustler.backend.api.chat.configuration.ChatTestRoutesConfiguration;
import com.gustler.backend.api.chat.domain.ChatMessage;
import com.gustler.backend.api.chat.infrastructure.mongo.ChatMongoContainer;
import com.gustler.backend.support.PostgresTestContainer;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainer.class, ChatTestRoutesConfiguration.class})
@DirtiesContext
class ChatWebSocketMongoIntegrationTest {

    private static final String ROOM = "204000057";
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Container
    private static final ChatMongoContainer MONGO = new ChatMongoContainer();

    private final List<ChatTestClient> clients = new ArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private ChatMessageRepository repository;

    @Autowired
    private MongoCollection<Document> chatMessagesCollection;

    @DynamicPropertySource
    static void chatProperties(DynamicPropertyRegistry registry) {
        registry.add("chat.enabled", () -> "true");
        registry.add("chat.mongodb-uri", MONGO::getConnectionString);
    }

    @AfterEach
    void closeClients() throws Exception {
        for (ChatTestClient client : clients) {
            client.close();
        }
    }

    @Test
    void 이력을_받고_보내면_ACK와_방송을_받으며_재전송은_중복_ACK만_받는다() throws Exception {
        // given
        chatMessagesCollection.deleteMany(new Document());
        Instant base = Instant.parse("2026-09-29T13:00:00Z");
        for (int index = 0; index < 12; index++) {
            repository.save(new ChatMessage(
                "68db0000000000000000%04x".formatted(index + 1), ROOM, "history-" + index, "author-x",
                "수다스러운 안양역", "이력 " + index, base.plusSeconds(index)
            ));
        }
        ChatTestClient sender = connect();
        ChatTestClient peer = connect();

        // when
        sender.send("""
            {"v":1,"type":"session.start","clientSessionId":"0f8fad5b-d9cb-469f-a165-70867728950e"}
            """);
        List<String> senderStart = receive(sender, 4);
        peer.send("""
            {"v":1,"type":"session.start","clientSessionId":"6ba7b810-9dad-41d1-80b4-00c04fd430c8"}
            """);
        List<String> peerStart = receive(peer, 4);

        // then
        assertThat(senderStart).extracting(this::type)
            .containsExactly("session.ready", "history.batch", "history.batch", "history.end");
        assertThat(peerStart).extracting(this::type)
            .containsExactly("session.ready", "history.batch", "history.batch", "history.end");
        assertSameShape(senderStart.getFirst(), "session.ready");
        assertThat(historyBodies(senderStart)).containsExactlyElementsOf(
            IntStream.range(0, 12).mapToObj(index -> "이력 " + index).toList()
        );
        assertThat(ChatContractExamples.parse(senderStart.get(1)).get("messages")).hasSize(10);
        assertThat(ChatContractExamples.parse(senderStart.get(1)).get("messages").get(0).get("createdAt").stringValue())
            .isEqualTo("2026-09-29T13:00:00Z");

        // when
        String send = """
            {"v":1,"type":"message.send","clientMessageId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","body":"  지금 자리 여유 있어요  "}
            """;
        sender.send(send);
        String ack = sender.next(WAIT);
        String senderCreated = sender.next(WAIT);
        String peerCreated = peer.next(WAIT);

        // then
        assertThat(type(ack)).isEqualTo("message.ack");
        assertSameShape(ack, "message.ack");
        JsonNode ackNode = ChatContractExamples.parse(ack);
        assertThat(ackNode.get("duplicate").booleanValue()).isFalse();
        assertThat(ackNode.get("message").get("body").stringValue()).isEqualTo("지금 자리 여유 있어요");
        assertThat(ackNode.get("message").get("createdAt").stringValue())
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z");
        assertThat(type(senderCreated)).isEqualTo("message.created");
        assertSameShape(senderCreated, "message.created");
        assertThat(ChatContractExamples.parse(senderCreated).get("message")).isEqualTo(ackNode.get("message"));
        assertThat(peerCreated).isEqualTo(senderCreated);

        // when
        Thread.sleep(1_100L);
        sender.send(send);
        String duplicateAck = sender.next(WAIT);

        // then
        JsonNode duplicateNode = ChatContractExamples.parse(duplicateAck);
        assertThat(type(duplicateAck)).isEqualTo("message.ack");
        assertThat(duplicateNode.get("duplicate").booleanValue()).isTrue();
        assertThat(duplicateNode.get("message")).isEqualTo(ackNode.get("message"));
        assertThat(sender.poll(Duration.ofSeconds(1))).isNull();
        assertThat(peer.poll(Duration.ofMillis(200))).isNull();
        assertThat(chatMessagesCollection.countDocuments(
            Filters.eq("clientMessageId", "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        )).isEqualTo(1);
    }

    @Test
    void 정확히_1024바이트인_프레임은_받는다() throws Exception {
        // given
        ChatTestClient client = connect();
        String sessionStart = """
            {"v":1,"type":"session.start","clientSessionId":"0f8fad5b-d9cb-469f-a165-70867728950e"}""";
        String frame = sessionStart + " ".repeat(ChatWebSocketHandler.MAX_FRAME_BYTES - utf8Length(sessionStart));

        // when
        client.send(frame);

        // then
        assertThat(utf8Length(frame)).isEqualTo(1_024);
        assertThat(type(client.next(WAIT))).isEqualTo("session.ready");
    }

    @Test
    void ASCII_1025자_프레임은_Tomcat이_1009로_닫는다() throws Exception {
        // given
        ChatTestClient client = connect();
        String sessionStart = """
            {"v":1,"type":"session.start","clientSessionId":"0f8fad5b-d9cb-469f-a165-70867728950e"}""";
        String frame = sessionStart + " ".repeat(ChatWebSocketHandler.MAX_FRAME_BYTES + 1 - utf8Length(sessionStart));

        // when
        client.send(frame);

        // then
        assertThat(frame).hasSize(1_025);
        assertThat(client.closeStatus(WAIT).getCode()).isEqualTo(1009);
        assertThat(client.poll(Duration.ZERO)).isNull();
    }

    @Test
    void 글자_수는_1024_이하지만_1024바이트를_넘는_한글_프레임은_INVALID_FRAME으로_닫는다() throws Exception {
        // given
        ChatTestClient client = connect();
        String frame = """
            {"v":1,"type":"message.send","clientMessageId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","body":"%s"}"""
            .formatted("가".repeat(340));

        // when
        client.send(frame);

        // then
        assertThat(frame.length()).isLessThanOrEqualTo(1_024);
        assertThat(utf8Length(frame)).isGreaterThan(1_024);
        JsonNode error = ChatContractExamples.parse(client.next(WAIT));
        assertThat(error.get("code").stringValue()).isEqualTo("INVALID_FRAME");
        assertThat(error.get("requestId").isNull()).isTrue();
        assertThat(error.get("fatal").booleanValue()).isTrue();
        assertThat(client.closeStatus(WAIT)).isEqualTo(CloseStatus.POLICY_VIOLATION.withReason("INVALID_FRAME"));
    }

    private ChatTestClient connect() throws Exception {
        ChatTestClient client = ChatTestClient.connect(port, ROOM, ChatTestClient.ALLOWED_ORIGIN);
        clients.add(client);
        return client;
    }

    private List<String> receive(ChatTestClient client, final int count) throws InterruptedException {
        List<String> frames = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            frames.add(client.next(WAIT));
        }
        return frames;
    }

    private List<String> historyBodies(List<String> frames) {
        List<String> bodies = new ArrayList<>();
        frames.stream()
            .map(ChatContractExamples::parse)
            .filter(frame -> "history.batch".equals(frame.get("type").stringValue()))
            .forEach(frame -> frame.get("messages").forEach(message -> bodies.add(message.get("body").stringValue())));
        return bodies;
    }

    private void assertSameShape(String frame, String exampleName) {
        JsonNode actual = ChatContractExamples.parse(frame);
        JsonNode example = ChatContractExamples.serverFrame(exampleName);
        assertThat(ChatContractExamples.fieldNames(actual))
            .containsExactlyElementsOf(ChatContractExamples.fieldNames(example));
        if (example.has("message")) {
            assertThat(ChatContractExamples.fieldNames(actual.get("message")))
                .containsExactlyElementsOf(ChatContractExamples.fieldNames(example.get("message")));
        }
    }

    private int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private String type(String frame) {
        return ChatContractExamples.parse(frame).get("type").stringValue();
    }
}
