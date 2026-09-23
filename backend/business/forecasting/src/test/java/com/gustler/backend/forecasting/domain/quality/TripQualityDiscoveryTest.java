package com.gustler.backend.forecasting.domain.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TripQualityDiscoveryTest {
    @Test
    void 조사를_재개할_때_기존의_종료_시각과_관측_연결_간격을_변경할_수_없다() {
        // given
        final Instant from = Instant.parse("2026-09-22T00:00:00Z");
        final Instant until = from.plusSeconds(3_600);
        final TripQualityDiscovery discovery = new TripQualityDiscovery(from, 1, until, 600, false);
        discovery.advance(from.plusSeconds(100), 33, 32, 32);

        // when, then
        assertThatNoException().isThrownBy(() -> discovery.verifyResume(until, 600));
        assertThatIllegalArgumentException().isThrownBy(() -> discovery.verifyResume(until.plusSeconds(1), 600));
        assertThatIllegalArgumentException().isThrownBy(() -> discovery.verifyResume(until, 601));
        assertThat(discovery.cursorBatchId()).isEqualTo(33);
        assertThat(discovery.cursorAt()).isEqualTo(from.plusSeconds(100));
        assertThat(discovery.completed()).isFalse();
    }
}
