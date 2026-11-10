package com.gustler.backend.api.board.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.gustler.backend.api.board.domain.VehicleForecast;
import org.junit.jupiter.api.Test;

class AlightingAwareForecastResponseTest {
    @Test
    void 하차_후_좌석과_출발_확률을_목표별로_전달한다() {
        ForecastResponse response = ForecastResponse.from(new VehicleForecast.Available(0.15, 3.7,
            "AFTER_ALIGHTING_ESTIMATE", "DEPARTURE_ZERO"));
        assertThat(response.expectedSeats()).isEqualTo(3.7);
        assertThat(response.afterAlightingSeatEstimate()).isEqualTo(3.7);
        assertThat(response.departureFullProbability()).isCloseTo(0.85, offset(1e-15));
        assertThat(response.seatAvailableProbability()).isEqualTo(0.15);
        assertThat(response.seatTarget()).isEqualTo("AFTER_ALIGHTING_ESTIMATE");
    }

    @Test
    void 아주_작은_출발_확률도_원본_값을_보존한다() {
        ForecastResponse response = ForecastResponse.from(new VehicleForecast.Available(1.0, 3.7,
            "AFTER_ALIGHTING_ESTIMATE", "DEPARTURE_ZERO", 1e-20));
        assertThat(response.departureFullProbability()).isEqualTo(1e-20);
    }

    @Test
    void 기존_출력을_하차_후_좌석으로_표시하지_않는다() {
        ForecastResponse response = ForecastResponse.from(new VehicleForecast.Available(0.8, 5.2));
        assertThat(response.seatTarget()).isEqualTo("FIRST_POST_STOP_OBSERVATION");
        assertThat(response.afterAlightingSeatEstimate()).isNull();
        assertThat(response.departureFullProbability()).isNull();
    }

    @Test
    void 미제공_예보는_수치와_목표가_없다() {
        ForecastResponse response = ForecastResponse.from(new VehicleForecast.Unavailable());
        assertThat(response.seatAvailableProbability()).isNull();
        assertThat(response.seatTarget()).isNull();
        assertThat(response.departureFullProbability()).isNull();
    }
}
