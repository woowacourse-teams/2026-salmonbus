package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardCachePolicyTest {
    private static final Clock SEOUL_CLOCK = Clock.fixed(
        Instant.EPOCH,
        ZoneId.of("Asia/Seoul")
    );

    private final BoardCachePolicy policy = new BoardCachePolicy(SEOUL_CLOCK);

    @ParameterizedTest
    @ValueSource(strings = {"07:00:00", "08:59:59", "17:00:00", "22:59:59"})
    void 오전_7시부터_8시와_오후_5시부터_10시는_15초를_캐시한다(String time) {
        // when
        Duration maxAge = maxAgeAt(time);

        // then
        assertThat(maxAge).isEqualTo(Duration.ofSeconds(15));
    }

    @ParameterizedTest
    @ValueSource(strings = {"01:00:00", "03:59:59"})
    void 오전_1시부터_3시는_600초를_캐시한다(String time) {
        // when
        Duration maxAge = maxAgeAt(time);

        // then
        assertThat(maxAge).isEqualTo(Duration.ofSeconds(600));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "00:59:59",
        "04:00:00",
        "06:59:59",
        "09:00:00",
        "16:59:59",
        "23:00:00"
    })
    void 그_밖의_시간은_20초를_캐시한다(String time) {
        // when
        Duration maxAge = maxAgeAt(time);

        // then
        assertThat(maxAge).isEqualTo(Duration.ofSeconds(20));
    }

    private Duration maxAgeAt(String time) {
        OffsetDateTime observedAt = OffsetDateTime.parse(
            "2026-08-27T" + time + "+09:00"
        );
        return policy.maxAgeAt(observedAt);
    }
}
