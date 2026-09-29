package com.gustler.backend.api.chat.infrastructure.mongo;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;

public final class ChatMongoClientSettings {

    static final int SERVER_SELECTION_TIMEOUT_SECONDS = 2;
    static final int CONNECT_TIMEOUT_SECONDS = 2;
    static final int READ_TIMEOUT_SECONDS = 5;
    static final int MAX_POOL_SIZE = 5;
    static final int MAX_WAIT_TIME_SECONDS = 2;

    private ChatMongoClientSettings() {
    }

    public static MongoClientSettings from(String uri) {
        return MongoClientSettings.builder()
            .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(SERVER_SELECTION_TIMEOUT_SECONDS, SECONDS))
            .applyToSocketSettings(socket -> socket
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, SECONDS))
            .applyToConnectionPoolSettings(pool -> pool
                .maxSize(MAX_POOL_SIZE)
                .maxWaitTime(MAX_WAIT_TIME_SECONDS, SECONDS))
            .applyConnectionString(new ConnectionString(uri.strip()))
            .build();
    }
}
