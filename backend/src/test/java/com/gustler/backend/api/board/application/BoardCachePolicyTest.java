package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoardCachePolicyTest {

    private final BoardCachePolicy policy = new BoardCachePolicy();

    @ParameterizedTest
    @CsvSource({
        "00:59:59, 20",
        "01:00:00, 600",
        "03:59:59, 600",
        "04:00:00, 20",
        "06:59:59, 20",
        "07:00:00, 15",
        "08:59:59, 15",
        "09:00:00, 20",
        "16:59:59, 20",
        "17:00:00, 15",
        "22:59:59, 15",
        "23:00:00, 20"
    })
    void KST_시간대별_수집_간격을_캐시_수명으로_사용한다(
        final String time,
        final long expectedSeconds
    ) {
        final OffsetDateTime observedAt = OffsetDateTime.parse(
            "2026-08-27T" + time + "+09:00"
        );

        assertThat(policy.maxAgeAt(observedAt))
            .isEqualTo(Duration.ofSeconds(expectedSeconds));
    }
}
