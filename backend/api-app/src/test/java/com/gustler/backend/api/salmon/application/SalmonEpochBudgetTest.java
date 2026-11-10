package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SalmonEpochBudgetTest {
    @Test
    void 기준선은_두_번까지_읽는다() {
        SalmonEpochBudget budget = new SalmonEpochBudget(2);

        assertThat(budget.budget()).isEqualTo(2);
        assertThat(budget.exhausted(2)).isFalse();
        assertThat(budget.exhausted(3)).isTrue();
    }

    @Test
    void 예산이_0이면_만들지_않는다() {
        assertThatThrownBy(() -> new SalmonEpochBudget(0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
