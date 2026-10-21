package com.gustler.backend.forecasting.domain.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class TripQualityDiscoveryTest {
    @Test
    void 조사를_재개할_때_기존의_종료_시각과_관측_연결_간격을_변경할_수_없다() {
        // when
        Instant from = Instant.parse("2026-09-22T00:00:00Z");
        Instant until = from.plusSeconds(3_600);
        TripQualityDiscovery discovery = new TripQualityDiscovery(from, 1, until, 600, false);
        discovery.advance(from.plusSeconds(100), 33, 32, 32);


        // then
        assertThatNoException().isThrownBy(() -> discovery.verifyResume(until, 600));

        // when
        Throwable phaseFailure1 = catchThrowable(() -> discovery.verifyResume(until.plusSeconds(1), 600));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure2 = catchThrowable(() -> discovery.verifyResume(until, 601));

        // then
        assertThat(phaseFailure2)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(discovery.cursorBatchId()).isEqualTo(33);
            softly.assertThat(discovery.cursorAt()).isEqualTo(from.plusSeconds(100));
            softly.assertThat(discovery.completed()).isFalse();
        }
    }
}
