package com.gustler.backend.quota.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArrivalQuotaPolicyTest {
    private static final int TOTAL = 40_000;
    private static final int KEY_LIMIT = 10_000;

    @Test
    void 키가_다섯_개_이상이어도_별칭마다_키별_한도를_둔다() {
        // when
        ArrivalQuotaPolicy policy = new ArrivalQuotaPolicy(TOTAL,
            Map.of("a", KEY_LIMIT, "b", KEY_LIMIT, "c", KEY_LIMIT, "d", KEY_LIMIT, "e", KEY_LIMIT, "f10", KEY_LIMIT));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(policy.keyLimits()).containsOnlyKeys("a", "b", "c", "d", "e", "f10");
            softly.assertThat(policy.dailyLimit()).isEqualTo(TOTAL);
        }
    }

    @Test
    void 전체_예산은_키_네_개_분량에_묶이지_않고_설정한_값을_쓴다() {
        // when
        ArrivalQuotaPolicy policy = new ArrivalQuotaPolicy(50_000,
            Map.of("a", KEY_LIMIT, "b", KEY_LIMIT, "c", KEY_LIMIT, "d", KEY_LIMIT, "e", KEY_LIMIT));

        // then
        assertThat(policy.dailyLimit()).isEqualTo(50_000);
    }

    @Test
    void 키별_한도는_코드_상한_없이_설정한_값을_쓰고_1보다_작으면_막는다() {
        // when
        ArrivalQuotaPolicy policy = new ArrivalQuotaPolicy(TOTAL, Map.of("a", 25_000, "e", 1_000));

        // then
        assertThat(policy.keyLimits()).containsEntry("a", 25_000).containsEntry("e", 1_000);

        // when
        Throwable phaseFailure1 = catchThrowable(() -> new ArrivalQuotaPolicy(TOTAL, Map.of("a", 0)));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"E", "e-1", "__total__", "abcdefghijklmnopq"})
    void 장부에_쓸_수_없는_별칭은_막는다(String alias) {
        // when
        Throwable caughtFailure = catchThrowable(
            () -> new ArrivalQuotaPolicy(TOTAL, Map.of("a", KEY_LIMIT, alias, KEY_LIMIT)));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 전체_예산이_없으면_막는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> new ArrivalQuotaPolicy(0, Map.of("a", KEY_LIMIT)));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }
}
