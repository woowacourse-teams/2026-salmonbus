package com.gustler.backend.forecasting.domain.model.alighting;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import org.junit.jupiter.api.Test;

class AlightingAwareOutputContractTest {

    @Test
    void 하차_후_좌석과_출발_만석_확률을_독립된_값으로_담는다() {
        SeatForecastResult result = SeatForecastResult.alighting(3.7, 0.85);
        assertSoftly(softly -> {
            softly.assertThat(result.expectedSeats()).isEqualTo(3.7);
            softly.assertThat(result.fullChance()).isEqualTo(0.85);
            softly.assertThat(result.seatTarget()).isEqualTo("AFTER_ALIGHTING_ESTIMATE");
            softly.assertThat(result.probabilityTarget()).isEqualTo("DEPARTURE_ZERO");
        });
    }

    @Test
    void 허용_범위를_벗어난_출력은_거부한다() {
        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> SeatForecastResult.alighting(
                Double.NaN,
                0.5
            )).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> SeatForecastResult.alighting(
                71,
                0.5
            )).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> SeatForecastResult.alighting(
                3,
                -0.1
            )).isInstanceOf(IllegalArgumentException.class);
        });
    }
}
