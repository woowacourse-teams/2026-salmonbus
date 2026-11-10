package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SalmonHorizonTest {
    @Test
    void 마감은_요청_시각부터_20분이다() {
        assertThat(new SalmonHorizon(Duration.ofMinutes(20)).horizon()).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    void 마감이_0이면_만들지_않는다() {
        assertThatThrownBy(() -> new SalmonHorizon(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
