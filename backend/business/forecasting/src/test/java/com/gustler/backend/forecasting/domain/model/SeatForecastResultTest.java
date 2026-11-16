package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeatForecastResultTest {
    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY})
    void 만석_확률은_0과_1_사이의_수다(final double notChance) {
        // when
        Throwable caughtFailure = catchThrowable(() -> SeatForecastResult.alighting(3.0, notChance));

        // then
        assertThat(caughtFailure).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 70.1, Double.NaN, Double.POSITIVE_INFINITY})
    void 좌석은_0_이상_70_이하의_유한한_수다(final double notSeats) {
        // when
        Throwable caughtFailure = catchThrowable(() -> SeatForecastResult.alighting(notSeats, 0.5));

        // then
        assertThat(caughtFailure).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 하차_후_좌석과_출발_만석_확률이_아닌_출력은_만들_수_없다() {
        // when
        Throwable seatTarget = catchThrowable(() -> new SeatForecastResult(3.0, 0.5, "FIRST_POST_STOP_OBSERVATION",
            SeatForecastResult.ALIGHTING_AWARE_PROBABILITY_TARGET));
        Throwable probabilityTarget = catchThrowable(() -> new SeatForecastResult(3.0, 0.5,
            SeatForecastResult.ALIGHTING_AWARE_SEAT_TARGET, "FIRST_POST_STOP_ZERO"));

        // then
        assertThat(seatTarget).isInstanceOf(IllegalArgumentException.class);
        assertThat(probabilityTarget).isInstanceOf(IllegalArgumentException.class);
    }
}
