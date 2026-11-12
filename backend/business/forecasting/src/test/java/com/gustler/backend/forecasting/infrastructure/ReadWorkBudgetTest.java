package com.gustler.backend.forecasting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReadWorkBudgetTest {
    @Test
    void 횟수_한도에_도달하면_새_읽기를_시작하지_않는다() {
        ReadWorkBudget budget = new ReadWorkBudget(2, Duration.ofSeconds(30), () -> 0L);
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void 시계가_경과하면_남은_횟수가_있어도_새_읽기를_시작하지_않는다() {
        AtomicLong time = new AtomicLong(10);
        ReadWorkBudget budget = new ReadWorkBudget(10, Duration.ofNanos(5), time::get);
        assertThat(budget.tryAcquire()).isTrue();
        time.set(15);
        assertThat(budget.available()).isFalse();
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void 양수가_아닌_예산은_거부한다() {
        assertThatThrownBy(() -> new ReadWorkBudget(0, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadWorkBudget(1, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
