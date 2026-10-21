package com.gustler.backend.api.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ObservationFreshnessTest {
    @ParameterizedTest
    @CsvSource({
        "2026-10-16T00:59:59.999999999+09:00, 5",
        "2026-10-16T01:00:00+09:00, 10",
        "2026-10-16T03:59:59.999999999+09:00, 10",
        "2026-10-16T04:00:00+09:00, 5",
        "2026-10-15T15:00:00Z, 5",
        "2026-10-15T16:00:00Z, 10",
        "2026-10-15T19:00:00Z, 5",
        "2026-10-16T01:00:00Z, 5",
        "2026-10-15T09:00:00-07:00, 10"
    })
    void 한국_관측_시각으로_시간대를_선택한다(String time, final int minutes) {
        // when
        OffsetDateTime observedAt = OffsetDateTime.parse(time);

        // then
        assertThat(ObservationFreshness.staleAt(observedAt))
            .isEqualTo(observedAt.plusMinutes(minutes));
        assertThat(Duration.between(observedAt, ObservationFreshness.staleAt(observedAt)))
            .isEqualTo(Duration.ofMinutes(minutes));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23})
    void 어느_시각의_신선도_창도_최대_창을_넘지_않는다(final int hour) {
        // given
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-10-16T00:00:00+09:00").plusHours(hour).plusMinutes(59);

        // when
        Duration window = Duration.between(observedAt, ObservationFreshness.staleAt(observedAt));

        // then
        assertThat(window).isLessThanOrEqualTo(ObservationFreshness.MAX_WINDOW);
    }
}
