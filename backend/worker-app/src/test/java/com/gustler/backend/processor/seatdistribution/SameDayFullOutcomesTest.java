package com.gustler.backend.processor.seatdistribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

class SameDayFullOutcomesTest {

    @Test
    void 실제_만석_수가_도착이_확인된_예보_수보다_많으면_만들어지지_않는다() {
        // when & then
        assertThat(catchThrowable(() -> new SameDayFullOutcomes(10, 11, 0.5)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 보정_전_만석_확률_평균이_1을_넘으면_만들어지지_않는다() {
        // when & then
        assertThat(catchThrowable(() -> new SameDayFullOutcomes(10, 5, 1.5)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 도착이_확인된_예보가_하나도_없어도_만들어진다() {
        // when
        SameDayFullOutcomes actual = new SameDayFullOutcomes(0, 0, 0.0);

        // then
        assertThat(actual.rowCount()).isZero();
    }
}
