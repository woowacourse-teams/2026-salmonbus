package com.gustler.backend.stations.domain;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StationFillThrottleTest {
    private static final Instant NOON = Instant.parse("2026-11-05T03:00:00Z");

    @Test
    void 차단_신호를_받으면_쉬고_연속이면_대기를_두_배로_늘린다() {
        // given
        StationFillThrottle throttle = new StationFillThrottle(
            Duration.ofSeconds(65), Duration.ofMinutes(10), 8000, 2000
        );

        // when
        throttle.blocked(NOON);
        Instant firstPause = throttle.pausedUntil();
        throttle.blocked(firstPause);

        // then
        assertSoftly(softly -> {
            softly.assertThat(firstPause).isEqualTo(NOON.plusSeconds(65));
            softly.assertThat(throttle.pausedUntil()).isEqualTo(firstPause.plusSeconds(130));
            softly.assertThat(throttle.mayCall(firstPause.plusSeconds(1))).isFalse();
        });
    }

    @Test
    void 하루_상한을_넘으면_다음_한국_날짜의_자정까지_멈춘다() {
        // given
        StationFillThrottle throttle = new StationFillThrottle(Duration.ofSeconds(65), Duration.ofMinutes(10), 2, 0);
        throttle.called(NOON);
        throttle.called(NOON);

        // when
        final boolean allowed = throttle.mayCall(NOON);

        // then
        assertSoftly(softly -> {
            softly.assertThat(allowed).isFalse();
            softly.assertThat(throttle.pauseReason()).isEqualTo("DAILY_LIMIT");
            softly.assertThat(throttle.pausedUntil()).isEqualTo(Instant.parse("2026-11-05T15:00:00Z"));
        });
    }

    @Test
    void 원천이_알려_준_남은_값이_하한_아래면_그날_멈춘다() {
        // given
        StationFillThrottle throttle = new StationFillThrottle(
            Duration.ofSeconds(65), Duration.ofMinutes(10), 8000, 2000
        );

        // when
        throttle.observedRemaining(1999, NOON);

        // then
        assertSoftly(softly -> {
            softly.assertThat(throttle.mayCall(NOON.plusSeconds(1))).isFalse();
            softly.assertThat(throttle.pauseReason()).isEqualTo("REMAINING_LOW");
        });
    }
}
