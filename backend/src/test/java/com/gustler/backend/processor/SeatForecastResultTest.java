package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SeatForecastResultTest {

    @Test
    void 만석_확률은_분포의_0석_남을_확률이다() {
        // given
        final SeatForecastResult result =
            new SeatForecastResult(SeatDistribution.of(0.2, 0.3, 0.5), 0.35);

        // when
        final double actual = result.pFull();

        // then
        assertThat(actual).isEqualTo(0.2);
    }

    @Test
    void 예보는_보정_전_만석_확률을_따로_들고_있다() {
        // given
        final SeatForecastResult result =
            new SeatForecastResult(SeatDistribution.of(0.2, 0.3, 0.5), 0.35);

        // when
        final double actual = result.pFullRaw();

        // then
        assertThat(actual).isEqualTo(0.35);
        assertThat(result.pFull()).isEqualTo(0.2);
    }
}
