package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SalmonAlphaTest {
    @Test
    void 도보_10분은_12분으로_올린다() {
        SalmonAlpha alpha = new SalmonAlpha(new BigDecimal("1.2"));

        assertThat(alpha.alpha()).isEqualByComparingTo("1.2");
        assertThat(alpha.scaledMillis(Duration.ofMinutes(10).toMillis()))
            .isEqualTo(Duration.ofMinutes(12).toMillis());
    }

    @Test
    void 배수가_1보다_작으면_만들지_않는다() {
        assertThatThrownBy(() -> new SalmonAlpha(new BigDecimal("0.9")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
