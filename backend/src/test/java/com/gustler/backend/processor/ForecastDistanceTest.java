package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ForecastDistanceTest {

    @Test
    void 예보는_다음_정류장부터_시작한다() {
        // when
        final ForecastDistance actual = new ForecastDistance(1);

        // then
        assertThat(actual.stopCount()).isEqualTo(1);
        assertThatThrownBy(() -> new ForecastDistance(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 도착_예정인_12개_정류장까지만_예보한다() {
        // when
        final ForecastDistance actual = new ForecastDistance(12);

        // then
        assertThat(actual.stopCount()).isEqualTo(12);
        assertThatThrownBy(() -> new ForecastDistance(13))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
