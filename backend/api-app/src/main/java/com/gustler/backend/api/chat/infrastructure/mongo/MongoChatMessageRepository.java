package com.gustler.backend.api.chat.infrastructure.mongo;

import static com.gustler.backend.api.chat.application.ChatMessageRepository.SaveResult.Status.CONFLICT;
import static com.gustler.backend.api.chat.application.ChatMessageRepository.SaveResult.Status.CREATED;
import static com.gustler.backend.api.chat.application.ChatMessageRepository.SaveResult.Status.DUPLICATE;

import com.gustler.backend.api.chat.application.ChatMessageRepository;
import com.gustler.backend.api.chat.domain.ChatMessage;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;

public final class MongoChatMessageRepository implements ChatMessageRepository {

    private final MongoCollection<Document> collection;

    public MongoChatMessageRepository(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    @Override
    public String nextId() {
        return new ObjectId().toHexString();
    }

    @Override
    public SaveResult save(ChatMessage message) {
        try {
            collection.insertOne(toDocument(message));
            return new SaveResult(CREATED, message);
        } catch (MongoWriteException exception) {
            if (exception.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw exception;
            }
            Document stored = collection.find(Filters.and(
                Filters.eq("roomId", message.roomId()),
                Filters.eq("clientMessageId", message.clientMessageId())
            )).first();
            if (stored == null) {
                throw exception;
            }
            ChatMessage existing = fromDocument(stored);
            final boolean sameRequest = existing.authorId().equals(message.authorId())
                && existing.body().equals(message.body());
            return new SaveResult(sameRequest ? DUPLICATE : CONFLICT, existing);
        }
    }

    @Override
    public List<ChatMessage> findRecent(String roomId, final int limit) {
        List<ChatMessage> newestFirst = new ArrayList<>();
        collection.find(Filters.eq("roomId", roomId))
            .sort(Sorts.descending("createdAt", "_id"))
            .limit(limit)
            .map(this::fromDocument)
            .into(newestFirst);
        return newestFirst.reversed();
    }

    private Document toDocument(ChatMessage message) {
        return new Document("_id", new ObjectId(message.id()))
            .append("roomId", message.roomId())
            .append("clientMessageId", message.clientMessageId())
            .append("authorId", message.authorId())
            .append("nickname", message.nickname())
            .append("body", message.body())
            .append("createdAt", Date.from(message.createdAt()));
    }

    private ChatMessage fromDocument(Document document) {
        return new ChatMessage(
            document.getObjectId("_id").toHexString(),
            document.getString("roomId"),
            document.getString("clientMessageId"),
            document.getString("authorId"),
            document.getString("nickname"),
            document.getString("body"),
            document.getDate("createdAt").toInstant()
        );
    }
}
