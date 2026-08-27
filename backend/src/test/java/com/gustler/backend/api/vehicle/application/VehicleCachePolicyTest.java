package com.gustler.backend.api.vehicle.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VehicleCachePolicyTest {

    private final VehicleCachePolicy policy = new VehicleCachePolicy();

    @ParameterizedTest
    @CsvSource({
        "2026-08-27T00:59:59+09:00, 20",
        "2026-08-27T01:00:00+09:00, 600",
        "2026-08-27T03:59:59+09:00, 600",
        "2026-08-27T04:00:00+09:00, 20",
        "2026-08-27T06:59:59+09:00, 20",
        "2026-08-27T07:00:00+09:00, 15",
        "2026-08-27T08:59:59+09:00, 15",
        "2026-08-27T09:00:00+09:00, 20",
        "2026-08-27T16:59:59+09:00, 20",
        "2026-08-27T17:00:00+09:00, 15",
        "2026-08-27T22:59:59+09:00, 15",
        "2026-08-27T23:00:00+09:00, 20"
    })
    void KST_수집_구간에_맞는_캐시_수명을_반환한다(
        final OffsetDateTime pollAt,
        final long expectedSeconds
    ) {
        assertThat(policy.maxAgeAt(pollAt)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }
}
