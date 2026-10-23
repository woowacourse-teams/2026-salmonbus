package com.gustler.backend.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenFamilyTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T09:00:00+09:00");
    private static final int CURRENT_GENERATION = 5;

    @Test
    void 현재_세대는_현재다() {
        // when
        RefreshTokenStatus actual = family(NOW.minusSeconds(1), NOW.plusDays(1)).statusOf(CURRENT_GENERATION, NOW);

        // then
        assertThat(actual).isEqualTo(RefreshTokenStatus.CURRENT);
    }

    @Test
    void 한_세대_전_토큰은_현재_세대를_발급한_지_10초_정각까지_유예다() {
        // when
        RefreshTokenStatus actual = family(NOW.minusSeconds(10), NOW.plusDays(1))
            .statusOf(CURRENT_GENERATION - 1, NOW);

        // then
        assertThat(actual).isEqualTo(RefreshTokenStatus.IN_GRACE);
    }

    @Test
    void 한_세대_전_토큰도_10초를_넘기면_재사용이다() {
        // when
        RefreshTokenStatus actual = family(NOW.minusSeconds(10).minusNanos(1_000), NOW.plusDays(1))
            .statusOf(CURRENT_GENERATION - 1, NOW);

        // then
        assertThat(actual).isEqualTo(RefreshTokenStatus.REUSED);
    }

    @Test
    void 두_세대_이상_전_토큰은_시간과_상관없이_재사용이다() {
        // when
        RefreshTokenStatus actual = family(NOW, NOW.plusDays(1)).statusOf(CURRENT_GENERATION - 2, NOW);

        // then
        assertThat(actual).isEqualTo(RefreshTokenStatus.REUSED);
    }

    @Test
    void 현재보다_앞선_세대는_발급한_적이_없다() {
        // when
        RefreshTokenStatus actual = family(NOW, NOW.plusDays(1)).statusOf(CURRENT_GENERATION + 1, NOW);

        // then
        assertThat(actual).isEqualTo(RefreshTokenStatus.FUTURE);
    }

    @Test
    void 만료_시각_정각부터_만료이고_세대보다_만료가_먼저다() {
        // given
        RefreshTokenFamily family = family(NOW.minusSeconds(1), NOW);

        // when
        RefreshTokenStatus current = family.statusOf(CURRENT_GENERATION, NOW);
        RefreshTokenStatus stale = family.statusOf(0, NOW);

        // then
        assertSoftly(softly -> {
            softly.assertThat(current).isEqualTo(RefreshTokenStatus.EXPIRED);
            softly.assertThat(stale).isEqualTo(RefreshTokenStatus.EXPIRED);
        });
    }

    private static RefreshTokenFamily family(OffsetDateTime rotatedAt, OffsetDateTime expiresAt) {
        return new RefreshTokenFamily(UUID.randomUUID(), UUID.randomUUID(), CURRENT_GENERATION, rotatedAt, expiresAt);
    }
}
