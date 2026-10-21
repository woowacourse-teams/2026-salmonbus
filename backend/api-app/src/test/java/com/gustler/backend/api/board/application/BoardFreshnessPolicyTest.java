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
        // when
        BoardFreshnessPolicy policy = policyAt("2026-08-26T23:05:00Z");

        // then
        assertThat(policy.staleAt(OBSERVED_AT))
            .isEqualTo(OffsetDateTime.parse("2026-08-27T08:05:00+09:00"));
        assertThat(policy.isStale(OBSERVED_AT)).isFalse();
    }

    @Test
    void 관측_후_5분을_넘으면_낡았다() {
        // when
        BoardFreshnessPolicy policy = policyAt("2026-08-26T23:05:00.000000001Z");

        // then
        assertThat(policy.isStale(OBSERVED_AT)).isTrue();
    }

    @Test
    void 심야_관측은_정확히_10분까지_유효하고_초과하면_낡았다() {
        // when
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-10-16T01:00:00+09:00");

        // then
        assertThat(policyAt("2026-10-15T16:10:00Z").staleAt(observedAt))
            .isEqualTo(observedAt.plusMinutes(10));
        assertThat(policyAt("2026-10-15T16:10:00Z").isStale(observedAt)).isFalse();
        assertThat(policyAt("2026-10-15T16:10:00.000000001Z").isStale(observedAt)).isTrue();
    }

    @Test
    void 조회가_4시를_지나도_관측_시각의_심야_유효기간을_유지한다() {
        // when
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-10-16T03:59:00+09:00");
        BoardFreshnessPolicy afterNight = policyAt("2026-10-15T19:05:00Z");

        // then
        assertThat(afterNight.staleAt(observedAt))
            .isEqualTo(OffsetDateTime.parse("2026-10-16T04:09:00+09:00"));
        assertThat(afterNight.isStale(observedAt)).isFalse();
        assertThat(policyAt("2026-10-15T19:09:00.000000001Z").isStale(observedAt)).isTrue();
    }

    private BoardFreshnessPolicy policyAt(String instant) {
        return new BoardFreshnessPolicy(
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
        );
    }
}
