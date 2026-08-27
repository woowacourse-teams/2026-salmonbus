package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeatForecastResultTest {

    @Test
    void 만석_확률은_분포의_0석_남을_확률이다() {
        // given
        final SeatForecastResult result =
            new SeatForecastResult(new SeatDistribution(List.of(0.2, 0.3, 0.5)), 0.35);

        // when
        final double actual = result.fullChance();

        // then
        assertThat(actual).isEqualTo(0.2);
    }

    @Test
    void 예보는_보정_전_만석_확률을_따로_들고_있다() {
        // given
        final SeatForecastResult result =
            new SeatForecastResult(new SeatDistribution(List.of(0.2, 0.3, 0.5)), 0.35);

        // when
        final double actual = result.fullChanceRaw();

        // then
        assertThat(actual).isEqualTo(0.35);
        assertThat(result.fullChance()).isEqualTo(0.2);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY})
    void 보정_전_만석_확률은_0과_1_사이의_수다(
        final double notChance
    ) {
        // when & then
        assertThatThrownBy(
            () -> new SeatForecastResult(new SeatDistribution(List.of(0.2, 0.3, 0.5)), notChance)
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
