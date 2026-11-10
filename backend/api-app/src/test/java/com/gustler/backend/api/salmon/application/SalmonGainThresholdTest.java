package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SalmonGainThresholdTest {
    @Test
    void 이득_역치는_0_5다() {
        assertThat(new SalmonGainThreshold(0.5).threshold()).isEqualTo(0.5);
    }

    @Test
    void 역치가_0이면_만들지_않는다() {
        assertThatThrownBy(() -> new SalmonGainThreshold(0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
