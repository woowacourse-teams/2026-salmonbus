package com.gustler.backend.api.chat.infrastructure.mongo;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.MongoClientSettings;
import com.mongodb.ServerAddress;
import org.junit.jupiter.api.Test;

class ChatMongoClientSettingsTest {

    @Test
    void 접속_문자열에_값이_없으면_코드의_기본값을_쓴다() {
        // when
        MongoClientSettings settings = ChatMongoClientSettings.from(
            "mongodb://127.0.0.1:27017/salmonbus_chat"
        );

        // then
        assertThat(settings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS)).isEqualTo(2_000L);
        assertThat(settings.getSocketSettings().getConnectTimeout(MILLISECONDS)).isEqualTo(2_000);
        assertThat(settings.getSocketSettings().getReadTimeout(MILLISECONDS)).isEqualTo(5_000);
        assertThat(settings.getConnectionPoolSettings().getMaxSize()).isEqualTo(5);
        assertThat(settings.getConnectionPoolSettings().getMaxWaitTime(MILLISECONDS)).isEqualTo(2_000L);
        assertThat(settings.getClusterSettings().getHosts()).containsExactly(new ServerAddress("127.0.0.1", 27017));
    }

    @Test
    void 접속_문자열에_값이_있으면_접속_문자열이_이긴다() {
        // when
        MongoClientSettings settings = ChatMongoClientSettings.from(
            "mongodb://127.0.0.1:27017/salmonbus_chat"
                + "?serverSelectionTimeoutMS=7000&connectTimeoutMS=3500&maxPoolSize=9"
                + "&socketTimeoutMS=8000&waitQueueTimeoutMS=3000"
        );

        // then
        assertThat(settings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS)).isEqualTo(7_000L);
        assertThat(settings.getSocketSettings().getConnectTimeout(MILLISECONDS)).isEqualTo(3_500);
        assertThat(settings.getSocketSettings().getReadTimeout(MILLISECONDS)).isEqualTo(8_000);
        assertThat(settings.getConnectionPoolSettings().getMaxSize()).isEqualTo(9);
        assertThat(settings.getConnectionPoolSettings().getMaxWaitTime(MILLISECONDS)).isEqualTo(3_000L);
    }

    @Test
    void 접속_문자열에_일부만_있으면_나머지는_기본값이다() {
        // when
        MongoClientSettings settings = ChatMongoClientSettings.from(
            "mongodb://127.0.0.1:27017/salmonbus_chat?maxPoolSize=3"
        );

        // then
        assertThat(settings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS)).isEqualTo(2_000L);
        assertThat(settings.getSocketSettings().getConnectTimeout(MILLISECONDS)).isEqualTo(2_000);
        assertThat(settings.getSocketSettings().getReadTimeout(MILLISECONDS)).isEqualTo(5_000);
        assertThat(settings.getConnectionPoolSettings().getMaxSize()).isEqualTo(3);
        assertThat(settings.getConnectionPoolSettings().getMaxWaitTime(MILLISECONDS)).isEqualTo(2_000L);
    }
}
