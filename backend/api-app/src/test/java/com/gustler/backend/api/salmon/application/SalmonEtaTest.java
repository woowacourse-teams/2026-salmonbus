package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SalmonEtaTest {
    @Test
    void 동점_폭은_0이다() {
        assertThat(new SalmonEta(0).eta()).isEqualTo(0);
    }

    @Test
    void 음수면_만들지_않는다() {
        assertThatThrownBy(() -> new SalmonEta(-0.01))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
