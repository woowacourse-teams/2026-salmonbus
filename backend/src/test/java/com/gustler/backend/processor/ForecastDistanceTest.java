package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForecastDistanceTest {

    @Test
    void 예보는_관측한_정류장의_다음_정류장부터_시작한다() {
        // when & then
        assertThatThrownBy(() -> new ForecastDistance(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 도착_예정인_12개_정류장까지만_예보한다() {
        // when & then
        assertThatThrownBy(() -> new ForecastDistance(13))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 12})
    void 다음_정류장부터_12정류장_앞까지_예보한다(
        final int stopCount
    ) {
        // when & then
        assertThatCode(() -> new ForecastDistance(stopCount))
            .doesNotThrowAnyException();
    }
}
