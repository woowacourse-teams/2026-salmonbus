package com.gustler.backend.api.chat.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatPropertiesTest {

    @Test
    void 연결_수_상한은_서버_전체_1000개와_IP마다_30개가_기본값이다() {
        // given
        ChatProperties properties = new ChatProperties();

        // when & then
        assertThat(properties.getMaxConnections()).isEqualTo(1_000);
        assertThat(properties.getMaxConnectionsPerAddress()).isEqualTo(30);
    }
}
