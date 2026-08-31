package com.gustler.backend.processor.a18;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class FullChancePriorShiftTest {

    private static final double TOLERANCE = 1e-15;
    private static final double SMALLEST_CHANCE = 1e-6;

    @Test
    void 확정된_예보가_49건이면_양_끝만_자른_값을_낸다() {
        // given
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(49, 49, 0.1);

        // when
        final double actual = FullChancePriorShift.shifted(1.0, outcomes);

        // then
        assertThat(actual).isEqualTo(1.0 - SMALLEST_CHANCE, within(TOLERANCE));
    }

    @Test
    void 확정된_예보가_하나도_없어도_양_끝을_자른_값을_낸다() {
        // when
        final double actual = FullChancePriorShift.shifted(0.0, null);

        // then
        assertThat(actual).isEqualTo(SMALLEST_CHANCE, within(TOLERANCE));
    }

    @Test
    void 확정된_예보가_50건이면_보정한다() {
        // given 절반이 만석이었는데 예보는 평균 10퍼센트만 만석이라고 봤다
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(50, 25, 0.1);

        // when
        final double actual = FullChancePriorShift.shifted(0.1, outcomes);

        // then
        assertThat(actual).isGreaterThan(0.1);
    }

    @Test
    void 실제_만석이_예보_평균보다_적으면_확률이_내린다() {
        // given
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(100, 0, 0.4);

        // when
        final double actual = FullChancePriorShift.shifted(0.4, outcomes);

        // then
        assertThat(actual).isLessThan(0.4);
    }

    @Test
    void 로짓에서_옮기는_폭은_3까지다() {
        // given 보정량이 13 을 넘는 자리인데 3 에서 막힌다
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(100, 100, SMALLEST_CHANCE);

        // when
        final double actual = FullChancePriorShift.shifted(0.5, outcomes);

        // then
        assertThat(actual).isEqualTo(1.0 / (1.0 + Math.exp(-3.0)), within(TOLERANCE));
    }

    @Test
    void 로짓에서_옮기는_폭은_마이너스_3까지다() {
        // given
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(100, 0, 1.0 - SMALLEST_CHANCE);

        // when
        final double actual = FullChancePriorShift.shifted(0.5, outcomes);

        // then
        assertThat(actual).isEqualTo(1.0 / (1.0 + Math.exp(3.0)), within(TOLERANCE));
    }
}
