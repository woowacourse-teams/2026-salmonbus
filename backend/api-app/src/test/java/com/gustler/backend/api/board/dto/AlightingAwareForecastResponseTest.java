package com.gustler.backend.api.board.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.board.domain.VehicleForecast;
import org.junit.jupiter.api.Test;

class AlightingAwareForecastResponseTest {

    @Test
    void 하차_후_좌석과_출발_확률을_목표와_함께_전달한다() {
        ForecastResponse response = ForecastResponse.from(new VehicleForecast.Available(
                0.15,
                3.7,
                "AFTER_ALIGHTING_ESTIMATE",
                "DEPARTURE_ZERO",
                0.85
        ));
        assertSoftly(softly -> {
            softly.assertThat(response.expectedSeats()).isEqualTo(3.7);
            softly.assertThat(response.afterAlightingSeatEstimate()).isEqualTo(3.7);
            softly.assertThat(response.departureFullProbability()).isEqualTo(0.85);
            softly.assertThat(response.seatAvailableProbability()).isEqualTo(0.15);
            softly.assertThat(response.seatTarget()).isEqualTo("AFTER_ALIGHTING_ESTIMATE");
            softly.assertThat(response.probabilityTarget()).isEqualTo("DEPARTURE_ZERO");
        });
    }

    @Test
    void 아주_작은_출발_확률도_원본_값을_보존한다() {
        ForecastResponse response = ForecastResponse.from(new VehicleForecast.Available(
                1.0,
                3.7,
                "AFTER_ALIGHTING_ESTIMATE",
                "DEPARTURE_ZERO",
                1e-20
        ));
        assertThat(response.departureFullProbability()).isEqualTo(1e-20);
    }

    @Test
    void 기대_좌석이_없으면_하차_후_좌석도_싣지_않고_확률과_목표는_전달한다() {
        ForecastResponse response = ForecastResponse.from(new VehicleForecast.Available(
                0.8,
                null,
                "AFTER_ALIGHTING_ESTIMATE",
                "DEPARTURE_ZERO",
                0.2
        ));
        assertSoftly(softly -> {
            softly.assertThat(response.expectedSeats()).isNull();
            softly.assertThat(response.afterAlightingSeatEstimate()).isNull();
            softly.assertThat(response.seatAvailableProbability()).isEqualTo(0.8);
            softly.assertThat(response.departureFullProbability()).isEqualTo(0.2);
            softly.assertThat(response.seatTarget()).isEqualTo("AFTER_ALIGHTING_ESTIMATE");
            softly.assertThat(response.probabilityTarget()).isEqualTo("DEPARTURE_ZERO");
        });
    }

    @Test
    void 미제공_예보는_수치와_목표가_없다() {
        ForecastResponse response = ForecastResponse.from(new VehicleForecast.Unavailable());
        assertSoftly(softly -> {
            softly.assertThat(response.seatAvailableProbability()).isNull();
            softly.assertThat(response.seatTarget()).isNull();
            softly.assertThat(response.probabilityTarget()).isNull();
            softly.assertThat(response.departureFullProbability()).isNull();
        });
    }
}
