package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SalmonWalkCapTest {
    @Test
    void 도보_합이_15분이면_허용하고_넘으면_거절한다() {
        SalmonWalkCap cap = new SalmonWalkCap(Duration.ofMinutes(15));

        assertThat(cap.limit()).isEqualTo(Duration.ofMinutes(15));
        assertThat(cap.exceeded(Duration.ofMinutes(15).toMillis())).isFalse();
        assertThat(cap.exceeded(Duration.ofMinutes(15).toMillis() + 1)).isTrue();
    }

    @Test
    void 상한이_0이면_만들지_않는다() {
        assertThatThrownBy(() -> new SalmonWalkCap(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
