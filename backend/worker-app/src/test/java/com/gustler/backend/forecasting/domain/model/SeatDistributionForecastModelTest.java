package com.gustler.backend.forecasting.domain.model;

import com.gustler.backend.forecasting.infrastructure.bundle.DummyBundle;
import com.gustler.backend.forecasting.infrastructure.bundle.LoadedBundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.forecasting.domain.model.SeatRangeException;
import com.gustler.backend.forecasting.domain.model.SeatForecastInput;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 예보 재료가 실제 계수 계산과 모델 입력 검증까지 연결되는지 확인한다. */
class SeatDistributionForecastModelTest {

    @TempDir
    Path directory;

    @Test
    void 모델이_지원하는_각_잔여석_수의_확률을_계산한다() {
        // given
        SeatDistributionForecastModel model = model();
        SeatForecastInput input = ForecastInputFixture.of("204000057");

        // when
        SeatForecastResult actual = model.predict(input);

        // then
        assertThat(actual.distribution().chanceBySeats()).hasSize(SeatGrid.SEAT_COUNT);
    }

    @Test
    void 계산한_잔여석별_확률을_모두_더하면_1이다() {
        // given
        SeatDistributionForecastModel model = model();
        SeatForecastInput input = ForecastInputFixture.of("204000057");

        // when
        SeatForecastResult actual = model.predict(input);

        // then
        assertThat(actual.distribution().chanceBySeats().stream()
            .mapToDouble(Double::doubleValue).sum()).isEqualTo(1.0, within(1e-12));
    }

    @Test
    void 계수_묶음이_지원하지_않는_노선이면_예보를_거부한다() {
        // given
        SeatDistributionForecastModel model = model();
        SeatForecastInput input = ForecastInputFixture.of("999999999");

        // when
        Throwable actual = catchThrowable(() -> model.predict(input));

        // then
        assertThat(actual).isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 현재_잔여석이_허용_범위여도_과거_최대값이_모델_상한을_넘으면_거부한다() {
        // given
        SeatDistributionForecastModel model = model();
        int currentSeats = SeatGrid.LARGEST_SEATS / 2;
        int historicalMaximum = SeatGrid.LARGEST_SEATS + 1;
        SeatForecastInput input = ForecastInputFixture.of("204000057", currentSeats, historicalMaximum);

        // when
        Throwable actual = catchThrowable(() -> model.predict(input));

        // then
        assertThat(actual).isInstanceOfSatisfying(SeatRangeException.class, e -> {
            assertThat(e.inputField()).isEqualTo("capacity");
            assertThat(e.inputValue()).isEqualTo(historicalMaximum);
        });
    }

    private SeatDistributionForecastModel model() {
        return new SeatDistributionForecastModel(
            DummyBundle.valid().loadAt(directory).predictor());
    }
}
