package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SalmonInVehicleCapTest {
    @Test
    void 차내_시간이_20분이면_허용하고_넘으면_거절한다() {
        SalmonInVehicleCap cap = new SalmonInVehicleCap(Duration.ofMinutes(20));

        assertThat(cap.limit()).isEqualTo(Duration.ofMinutes(20));
        assertThat(cap.exceeded(Duration.ofMinutes(20).toMillis())).isFalse();
        assertThat(cap.exceeded(Duration.ofMinutes(20).toMillis() + 1)).isTrue();
    }

    @Test
    void 상한이_0이면_만들지_않는다() {
        assertThatThrownBy(() -> new SalmonInVehicleCap(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
