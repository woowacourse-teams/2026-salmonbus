package com.gustler.backend.api.vehicle.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class VehicleFreshnessPolicyTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-27T03:00:00Z"),
        ZoneId.of("Asia/Seoul")
    );

    private final VehicleFreshnessPolicy policy = new VehicleFreshnessPolicy(CLOCK);

    @Test
    void 관측_시각에서_5분_뒤를_staleAt으로_계산한다() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-08-27T11:55:00+09:00");

        assertThat(policy.staleAt(observedAt))
            .isEqualTo(OffsetDateTime.parse("2026-08-27T12:00:00+09:00"));
    }

    @Test
    void 현재_시각이_staleAt을_초과해야만_오래된_관측이다() {
        OffsetDateTime boundary = OffsetDateTime.parse("2026-08-27T12:00:00+09:00");
        OffsetDateTime exceeded = boundary.minusNanos(1);

        assertThat(policy.isStale(boundary)).isFalse();
        assertThat(policy.isStale(exceeded)).isTrue();
    }

    @Test
    void 심야_관측의_staleAt은_UTC_입력과_4시_이후_조회에서도_10분이다() {
        var observedAt = OffsetDateTime.parse("2026-10-15T18:59:00Z");
        var atExpiry = new VehicleFreshnessPolicy(Clock.fixed(
            Instant.parse("2026-10-15T19:09:00Z"), ZoneId.of("UTC")));
        var staleAt = atExpiry.staleAt(observedAt);

        assertThat(staleAt).isEqualTo(OffsetDateTime.parse("2026-10-15T19:09:00Z"));
        assertThat(atExpiry.isStale(staleAt)).isFalse();
        assertThat(new VehicleFreshnessPolicy(Clock.fixed(
            Instant.parse("2026-10-15T19:09:00.000000001Z"), ZoneId.of("UTC")))
            .isStale(staleAt)).isTrue();
    }
}
