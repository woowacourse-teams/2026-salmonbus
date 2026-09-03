package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ForecastWriteBarrierStateTest {

    @Test
    void exposesConsecutiveAndTotalSkipsAndResetsOnlyTheConsecutiveCount() {
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        ForecastWriteBarrierState state = new ForecastWriteBarrierState(
            Clock.fixed(now, ZoneOffset.UTC));

        for (int cycle = 0; cycle < 6; cycle++) {
            state.recordSkipped("WRITES_PAUSED");
        }
        assertThat(state.snapshot().skippedCycles()).isEqualTo(6);
        assertThat(state.snapshot().consecutiveSkippedCycles()).isEqualTo(6);
        assertThat(state.snapshot().lastSkippedAt()).isEqualTo(now);
        assertThat(state.snapshot().lastSkipReason()).isEqualTo("WRITES_PAUSED");

        state.recordEntered();
        assertThat(state.snapshot().skippedCycles()).isEqualTo(6);
        assertThat(state.snapshot().consecutiveSkippedCycles()).isZero();
    }
}
