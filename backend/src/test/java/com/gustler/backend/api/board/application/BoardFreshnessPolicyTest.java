package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BoardFreshnessPolicyTest {

    private static final OffsetDateTime OBSERVED_AT = OffsetDateTime.parse(
        "2026-08-27T08:00:00+09:00"
    );

    @Test
    void 관측_후_5분까지는_유효하다() {
        BoardFreshnessPolicy policy = policyAt("2026-08-26T23:05:00Z");

        assertThat(policy.staleAt(OBSERVED_AT))
            .isEqualTo(OffsetDateTime.parse("2026-08-27T08:05:00+09:00"));
        assertThat(policy.isStale(OBSERVED_AT)).isFalse();
    }

    @Test
    void 관측_후_5분을_넘으면_낡았다() {
        BoardFreshnessPolicy policy = policyAt("2026-08-26T23:05:00.000000001Z");

        assertThat(policy.isStale(OBSERVED_AT)).isTrue();
    }

    private BoardFreshnessPolicy policyAt(String instant) {
        return new BoardFreshnessPolicy(
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
        );
    }
}
