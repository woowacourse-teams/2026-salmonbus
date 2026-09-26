package com.gustler.backend.forecasting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ForecastPolicyTest {

    @Test
    void 기본_5분과_확장한_신선도_창을_모두_허용한다() {
        for (Duration duration : new Duration[] {Duration.ofMinutes(5), Duration.ofMinutes(6), Duration.ofHours(1)}) {
            assertThat(new ForecastPolicy(duration, 20, 3000, 400).staleness()).isEqualTo(duration);
        }
    }

    @Test
    void 신선도_상한은_한_시간이다() {
        assertThat(ForecastPolicy.MAX_STALENESS).isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> new ForecastPolicy(Duration.ofHours(1).plusSeconds(1), 20, 3000, 400))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
