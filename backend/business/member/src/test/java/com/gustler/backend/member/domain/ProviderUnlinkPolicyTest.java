package com.gustler.backend.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ProviderUnlinkPolicyTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T09:00:00+09:00");

    @Test
    void 기다리는_시간은_1분에서_실패할_때마다_두_배가_되고_6시간을_넘지_않는다() {
        // when & then
        assertSoftly(softly -> {
            softly.assertThat(ProviderUnlinkPolicy.backoff(0)).isEqualTo(Duration.ofMinutes(1));
            softly.assertThat(ProviderUnlinkPolicy.backoff(1)).isEqualTo(Duration.ofMinutes(2));
            softly.assertThat(ProviderUnlinkPolicy.backoff(8)).isEqualTo(Duration.ofMinutes(256));
            softly.assertThat(ProviderUnlinkPolicy.backoff(9)).isEqualTo(Duration.ofHours(6));
            softly.assertThat(ProviderUnlinkPolicy.backoff(1_000)).isEqualTo(Duration.ofHours(6));
        });
    }

    @Test
    void 첫_실패는_1분_뒤에_다시_시도한다() {
        // when
        UnlinkFailure actual = ProviderUnlinkPolicy.afterFailure(0, NOW);

        // then
        assertThat(actual).isEqualTo(new UnlinkFailure(1, UnlinkStatus.PENDING, NOW.plusMinutes(1)));
    }

    @Test
    void 아홉_번째_실패까지는_다시_시도하고_열_번째_실패에서_포기한다() {
        // when
        UnlinkFailure ninth = ProviderUnlinkPolicy.afterFailure(8, NOW);
        UnlinkFailure tenth = ProviderUnlinkPolicy.afterFailure(9, NOW);

        // then
        assertSoftly(softly -> {
            softly.assertThat(ninth).isEqualTo(new UnlinkFailure(9, UnlinkStatus.PENDING, NOW.plusMinutes(256)));
            softly.assertThat(tenth).isEqualTo(new UnlinkFailure(10, UnlinkStatus.GAVE_UP, NOW));
        });
    }

    @Test
    void 실패_원인은_200자에서_자르고_코드포인트를_쪼개지_않는다() {
        // given
        String emoji = "😀";

        // when
        String actual = ProviderUnlinkPolicy.errorOf(emoji.repeat(300));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.codePointCount(0, actual.length())).isEqualTo(200);
            softly.assertThat(actual).isEqualTo(emoji.repeat(200));
        });
    }

    @Test
    void 실패_원인이_없거나_비어_있으면_고정_문구다() {
        // when & then
        assertSoftly(softly -> {
            softly.assertThat(ProviderUnlinkPolicy.errorOf(null)).isEqualTo(ProviderUnlinkPolicy.UNKNOWN_ERROR);
            softly.assertThat(ProviderUnlinkPolicy.errorOf("  ")).isEqualTo(ProviderUnlinkPolicy.UNKNOWN_ERROR);
            softly.assertThat(ProviderUnlinkPolicy.errorOf(" timeout ")).isEqualTo("timeout");
        });
    }
}
