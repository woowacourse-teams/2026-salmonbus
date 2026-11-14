package com.gustler.backend.forecasting.domain.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SourceDatesTest {
    @Test
    void 한국_자정_직전의_예약은_그날이다() {
        // when
        LocalDate actual = SourceDates.of(Instant.parse("2026-11-15T14:59:59.999Z"));

        // then
        assertThat(actual).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void 한국_자정부터의_예약은_다음_날이다() {
        // when
        LocalDate actual = SourceDates.of(Instant.parse("2026-11-15T15:00:00Z"));

        // then
        assertThat(actual).isEqualTo(LocalDate.of(2026, 11, 16));
    }
}
