package com.gustler.backend.forecasting.domain.model.alighting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import org.junit.jupiter.api.Test;

class AlightingAwareOutputContractTest {
    @Test
    void 독립_좌석과_확률로_좌석_분포를_만들지_않는다() {
        SeatForecastResult result = SeatForecastResult.alighting(3.7, 0.85);
        assertThat(result.distribution()).isNull();
        assertThat(result.expectedSeats()).isEqualTo(3.7);
        assertThat(result.fullChance()).isEqualTo(0.85);
        assertThat(result.fullChanceRaw()).isEqualTo(0.85);
        assertThat(result.seatTarget()).isEqualTo("AFTER_ALIGHTING_ESTIMATE");
    }

    @Test
    void 허용_범위를_벗어난_출력은_거부한다() {
        assertThatThrownBy(() -> SeatForecastResult.alighting(Double.NaN, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatForecastResult.alighting(71, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatForecastResult.alighting(3, -0.1)).isInstanceOf(IllegalArgumentException.class);
    }
}
