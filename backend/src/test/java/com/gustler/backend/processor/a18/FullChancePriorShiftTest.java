package com.gustler.backend.processor.a18;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * 오늘 이미 도착이 확인된 예보들의 성적으로 만석 확률을 옮긴다.
 *
 * <p>예보가 오늘따라 만석을 계속 놓치고 있으면 확률을 올리고, 반대면 내린다.
 * 옮기는 폭에는 한도가 있다. 하루 성적이 몇 건 안 될 때 한 건이 확률을 끝까지 밀어 버리는 것을
 * 막는 자리다. 성적이 50건에 못 미치면 아예 안 옮긴다.
 */
class FullChancePriorShiftTest {

    private static final double TOLERANCE = 1e-15;
    private static final double SMALLEST_CHANCE = 1e-6;

    @Test
    void 오늘_도착이_확인된_예보가_49건이면_만석_확률을_안_옮긴다() {
        // given 예보 평균과 실제가 크게 어긋났는데도 건수가 모자란다
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(49, 49, 0.1);

        // when
        final double actual = FullChancePriorShift.shifted(0.3, outcomes);

        // then
        assertThat(actual).isEqualTo(0.3, within(TOLERANCE));
    }

    @Test
    void 오늘_도착이_확인된_예보가_하나도_없으면_만석_확률을_안_옮긴다() {
        // when
        final double actual = FullChancePriorShift.shifted(0.3, null);

        // then
        assertThat(actual).isEqualTo(0.3, within(TOLERANCE));
    }

    @Test
    void 오늘_도착이_확인된_예보가_50건이면_만석_확률을_옮긴다() {
        // given 절반이 만석이었는데 예보는 평균 10퍼센트만 만석이라고 봤다
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(50, 25, 0.1);

        // when
        final double actual = FullChancePriorShift.shifted(0.1, outcomes);

        // then
        assertThat(actual).isGreaterThan(0.1);
    }

    @Test
    void 오늘_예보보다_실제로_만석이_많았으면_만석_확률이_오른다() {
        // given 예보는 평균 10퍼센트를 봤는데 100건 중 50건이 만석이었다
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(100, 50, 0.1);

        // when
        final double actual = FullChancePriorShift.shifted(0.4, outcomes);

        // then
        assertThat(actual).isGreaterThan(0.4);
    }

    @Test
    void 오늘_예보보다_실제로_만석이_적었으면_만석_확률이_내린다() {
        // given 예보는 평균 40퍼센트를 봤는데 100건 중 한 건도 만석이 아니었다
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(100, 0, 0.4);

        // when
        final double actual = FullChancePriorShift.shifted(0.4, outcomes);

        // then
        assertThat(actual).isLessThan(0.4);
    }

    @Test
    void 오늘_전부_만석이었어도_만석_확률을_한도까지만_올린다() {
        // given 성적대로면 확률을 훨씬 더 올려야 하는 자리다
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(100, 100, SMALLEST_CHANCE);

        // when 반반이던 확률에서 시작한다
        final double actual = FullChancePriorShift.shifted(0.5, outcomes);

        // then 한도까지만 올라 95퍼센트에서 멈춘다
        assertThat(actual).isEqualTo(1.0 / (1.0 + Math.exp(-3.0)), within(TOLERANCE));
    }

    @Test
    void 오늘_하나도_만석이_아니었어도_만석_확률을_한도까지만_내린다() {
        // given
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(100, 0, 1.0 - SMALLEST_CHANCE);

        // when
        final double actual = FullChancePriorShift.shifted(0.5, outcomes);

        // then
        assertThat(actual).isEqualTo(1.0 / (1.0 + Math.exp(3.0)), within(TOLERANCE));
    }

    @Test
    void 만석_확률이_100퍼센트로_굳지_않는다() {
        // when 허들이 1 을 내도 그대로 안 내보낸다
        final double actual = FullChancePriorShift.shifted(1.0, null);

        // then
        assertThat(actual).isLessThan(1.0);
    }

    @Test
    void 만석_확률이_0퍼센트로_굳지_않는다() {
        // when
        final double actual = FullChancePriorShift.shifted(0.0, null);

        // then
        assertThat(actual).isGreaterThan(0.0);
    }
}
