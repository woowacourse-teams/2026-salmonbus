package com.gustler.backend.api.chat.infrastructure.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.chat.application.ChatMessageRepository.SaveResult;
import com.gustler.backend.api.chat.domain.ChatMessage;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MongoChatMessageRepositoryIntegrationTest {

    private static final String ROOM = "204000057";
    private static final String OTHER_ROOM = "234000050";
    private static final Instant BASE = Instant.parse("2026-09-29T13:00:00.000Z");

    @Container
    private static final ChatMongoContainer MONGO = new ChatMongoContainer();

    private static MongoClient client;

    private MongoCollection<Document> collection;
    private MongoChatMessageRepository repository;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(ChatMongoClientSettings.from(MONGO.getConnectionString()));
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @BeforeEach
    void setUp() {
        collection = client.getDatabase("salmonbus_chat").getCollection("messages");
        collection.deleteMany(new Document());
        repository = new MongoChatMessageRepository(collection);
    }

    @Test
    void 새_메시지는_CREATED로_저장한다() {
        // given
        ChatMessage message = message(1, ROOM, "7c9e6679-7425-40de-944b-e07fc1f90ae7", "author-a", "지금 자리 여유 있어요", BASE);

        // when
        SaveResult result = repository.save(message);

        // then
        assertThat(result).isEqualTo(new SaveResult(SaveResult.Status.CREATED, message));
        assertThat(repository.findRecent(ROOM, 50)).containsExactly(message);
    }

    @Test
    void 같은_ID_같은_작성자_같은_본문이면_DUPLICATE로_저장본을_돌려준다() {
        // given
        ChatMessage stored = message(1, ROOM, "7c9e6679-7425-40de-944b-e07fc1f90ae7", "author-a", "지금 자리 여유 있어요", BASE);
        repository.save(stored);
        ChatMessage retried = message(2, ROOM, "7c9e6679-7425-40de-944b-e07fc1f90ae7", "author-a", "지금 자리 여유 있어요",
            BASE.plusSeconds(3));

        // when
        SaveResult result = repository.save(retried);

        // then
        assertThat(result).isEqualTo(new SaveResult(SaveResult.Status.DUPLICATE, stored));
        assertThat(collection.countDocuments()).isEqualTo(1);
    }

    @Test
    void 같은_ID에_본문이나_작성자가_다르면_CONFLICT로_저장본을_돌려준다() {
        // given
        ChatMessage stored = message(1, ROOM, "7c9e6679-7425-40de-944b-e07fc1f90ae7", "author-a", "처음 내용", BASE);
        repository.save(stored);

        // when
        SaveResult otherBody = repository.save(
            message(2, ROOM, "7c9e6679-7425-40de-944b-e07fc1f90ae7", "author-a", "다른 내용", BASE.plusSeconds(1)));
        SaveResult otherAuthor = repository.save(
            message(3, ROOM, "7c9e6679-7425-40de-944b-e07fc1f90ae7", "author-b", "처음 내용", BASE.plusSeconds(2)));

        // then
        assertThat(otherBody).isEqualTo(new SaveResult(SaveResult.Status.CONFLICT, stored));
        assertThat(otherAuthor).isEqualTo(new SaveResult(SaveResult.Status.CONFLICT, stored));
        assertThat(collection.countDocuments()).isEqualTo(1);
    }

    @Test
    void 방이_다르면_같은_ID도_따로_저장한다() {
        // when
        SaveResult first = repository.save(
            message(1, ROOM, "7c9e6679-7425-40de-944b-e07fc1f90ae7", "author-a", "안녕", BASE));
        SaveResult second = repository.save(
            message(2, OTHER_ROOM, "7c9e6679-7425-40de-944b-e07fc1f90ae7", "author-a", "안녕", BASE));

        // then
        assertThat(first.status()).isEqualTo(SaveResult.Status.CREATED);
        assertThat(second.status()).isEqualTo(SaveResult.Status.CREATED);
    }

    @Test
    void 최근_50개를_오래된_순으로_주고_같은_시각은_id로_가르며_다른_방은_섞지_않는다() {
        // given
        List<ChatMessage> room = new ArrayList<>();
        for (int index = 0; index < 52; index++) {
            room.add(message(100 + index, ROOM, "room-" + index, "author-a", "메시지 " + index, BASE.plusMillis(index * 10L)));
        }
        Instant tied = BASE.plusSeconds(60);
        for (int index = 0; index < 3; index++) {
            room.add(message(300 - index, ROOM, "tied-" + index, "author-a", "같은 시각 " + index, tied));
        }
        List<ChatMessage> otherRoom = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            otherRoom.add(message(400 + index, OTHER_ROOM, "other-" + index, "author-b", "다른 방 " + index,
                BASE.plusSeconds(120 + index)));
        }
        room.forEach(repository::save);
        otherRoom.forEach(repository::save);

        // when
        List<ChatMessage> recent = repository.findRecent(ROOM, 50);

        // then
        List<ChatMessage> expected = room.stream()
            .sorted(Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::id))
            .toList();
        assertThat(recent).containsExactlyElementsOf(expected.subList(expected.size() - 50, expected.size()));
        assertThat(recent.subList(47, 50)).extracting(ChatMessage::clientMessageId)
            .containsExactly("tied-2", "tied-1", "tied-0");
        assertThat(recent).allSatisfy(message -> assertThat(message.roomId()).isEqualTo(ROOM));
    }

    @Test
    void 초기화_스크립트가_두_인덱스를_만들고_TTL_인덱스는_없다() {
        // when
        Map<String, Document> indexes = collection.listIndexes().into(new ArrayList<>()).stream()
            .collect(Collectors.toMap(index -> index.getString("name"), index -> index));

        // then
        assertThat(indexes).containsOnlyKeys("_id_", "room_client_message_unique", "room_recent");
        Document unique = indexes.get("room_client_message_unique");
        assertThat(unique.getBoolean("unique")).isTrue();
        assertThat(ascendingKeys(unique)).containsExactly("roomId", "clientMessageId");
        Document recent = indexes.get("room_recent");
        assertThat(recent.getBoolean("unique", false)).isFalse();
        assertThat(ascendingKeys(recent)).containsExactly("roomId", "createdAt", "_id");
        assertThat(indexes.values()).noneMatch(index -> index.containsKey("expireAfterSeconds"));
    }

    @Test
    void 새_ID는_저장할_수_있는_ObjectId_문자열이고_매번_다르다() {
        // when
        String first = repository.nextId();
        String second = repository.nextId();
        SaveResult saved = repository.save(message(first, "7c9e6679-7425-40de-944b-e07fc1f90ae7"));

        // then
        assertThat(first).matches("[0-9a-f]{24}");
        assertThat(second).matches("[0-9a-f]{24}").isNotEqualTo(first);
        assertThat(saved.status()).isEqualTo(SaveResult.Status.CREATED);
        assertThat(repository.findRecent(ROOM, 50)).extracting(ChatMessage::id).containsExactly(first);
    }

    @Test
    void 앱은_인덱스를_만들지_않는다() {
        // given
        MongoCollection<Document> untouched = client.getDatabase("salmonbus_chat").getCollection("messages_without_init");
        untouched.drop();
        MongoChatMessageRepository withoutInit = new MongoChatMessageRepository(untouched);

        // when
        withoutInit.save(message(1, ROOM, "7c9e6679-7425-40de-944b-e07fc1f90ae7", "author-a", "안녕", BASE));
        withoutInit.findRecent(ROOM, 50);

        // then
        assertThat(untouched.listIndexes().into(new ArrayList<>()))
            .extracting(index -> index.getString("name"))
            .containsExactly("_id_");
    }

    private List<String> ascendingKeys(Document index) {
        Document key = index.get("key", Document.class);
        assertThat(key.values()).allSatisfy(direction -> assertThat(((Number) direction).intValue()).isEqualTo(1));
        return List.copyOf(key.keySet());
    }

    private ChatMessage message(String id, String clientMessageId) {
        return new ChatMessage(id, ROOM, clientMessageId, "author-a", "졸린 범계역", "안녕", BASE);
    }

    private ChatMessage message(
        final int sequence,
        String roomId,
        String clientMessageId,
        String authorId,
        String body,
        Instant createdAt
    ) {
        return new ChatMessage(
            "68db0000000000000000%04x".formatted(sequence),
            roomId,
            clientMessageId,
            authorId,
            "졸린 범계역",
            body,
            createdAt
        );
    }
}
