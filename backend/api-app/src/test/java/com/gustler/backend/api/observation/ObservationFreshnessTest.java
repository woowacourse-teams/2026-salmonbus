package com.gustler.backend.api.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    void selectsTheWindowFromTheObservationTimeInKorea(String time, final int minutes) {
        var observedAt = OffsetDateTime.parse(time);

        assertThat(ObservationFreshness.staleAt(observedAt))
            .isEqualTo(observedAt.plusMinutes(minutes));
        assertThat(Duration.between(observedAt, ObservationFreshness.staleAt(observedAt)))
            .isEqualTo(Duration.ofMinutes(minutes));
    }
}
