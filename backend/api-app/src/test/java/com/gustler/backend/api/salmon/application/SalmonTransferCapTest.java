package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SalmonTransferCapTest {
    @Test
    void 환승_한_번까지_허용한다() {
        SalmonTransferCap cap = new SalmonTransferCap(1);

        assertThat(cap.limit()).isEqualTo(1);
        assertThat(cap.exceeded(1)).isFalse();
        assertThat(cap.exceeded(2)).isTrue();
    }

    @Test
    void 음수면_만들지_않는다() {
        assertThatThrownBy(() -> new SalmonTransferCap(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
