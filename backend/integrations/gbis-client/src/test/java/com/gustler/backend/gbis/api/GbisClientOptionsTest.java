package com.gustler.backend.gbis.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class GbisClientOptionsTest {

    private static final String BASE_URL = "https://gbis.test";
    private static final String SERVICE_KEY = "fake-key-a";
    private static final String SERVICE_KEY_B = "fake-key-b";

    @Test
    void 별칭으로_그_슬롯의_인증키를_꺼낸다() {
        // given
        GbisClientOptions options = new GbisClientOptions(BASE_URL,
            List.of(new GbisKey(GbisKey.PRIMARY, SERVICE_KEY), new GbisKey("b", SERVICE_KEY_B)));

        // when
        String actual = options.serviceKeyOf("b");

        // then
        assertThat(actual).isEqualTo(SERVICE_KEY_B);
    }

    @Test
    void 설정에_없는_슬롯의_키는_꺼내지_못한다() {
        // given
        GbisClientOptions options = new GbisClientOptions(BASE_URL, SERVICE_KEY);

        // when & then
        assertThatThrownBy(() -> options.serviceKeyOf("b"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 문자열로_바꾸면_모든_슬롯의_인증키를_가린다() {
        // given
        GbisClientOptions options = new GbisClientOptions(BASE_URL,
            List.of(new GbisKey(GbisKey.PRIMARY, SERVICE_KEY), new GbisKey("b", SERVICE_KEY_B)));

        // when
        String actual = options.toString();

        // then
        assertThat(actual)
            .doesNotContain(SERVICE_KEY)
            .doesNotContain(SERVICE_KEY_B);
    }
}
