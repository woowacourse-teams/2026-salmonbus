package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GbisKeyTest {

    private static final String SERVICE_KEY = "fake-key-b";

    @Test
    void 문자열로_바꾸면_키_값_없이_별칭만_보인다() {
        // given
        GbisKey key = new GbisKey("b", SERVICE_KEY);

        // when
        String actual = key.toString();

        // then
        assertThat(actual).contains("alias=b").doesNotContain(SERVICE_KEY);
    }
}
