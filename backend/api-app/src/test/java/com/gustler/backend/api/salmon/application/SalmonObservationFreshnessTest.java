package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SalmonObservationFreshnessTest {
    @Test
    void 만료_시각부터는_예보를_쓰지_않는다() {
        Instant staleAt = Instant.parse("2026-11-11T03:00:00Z");

        assertThat(SalmonObservationFreshness.usable(staleAt.minusMillis(1), staleAt)).isTrue();
        assertThat(SalmonObservationFreshness.usable(staleAt, staleAt)).isFalse();
        assertThat(SalmonObservationFreshness.usable(staleAt.plusMillis(1), staleAt)).isFalse();
    }
}
