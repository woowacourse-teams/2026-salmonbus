package com.gustler.backend.processor.seatdistribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.processor.SeatForecastResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 예보 재료가 계수 계산까지 실제로 흘러가는지 본다.
 *
 * <p>여기가 이어지기 전에는 계수를 다 읽어 놓고도 예보가 한 줄도 안 나갔다.
 */
class SeatDistributionForecastModelTest {

    @TempDir
    Path directory;

    @Test
    void 예보_재료_하나가_좌석_71칸_확률이_된다() {
        // when
        SeatForecastResult actual = model().predict(ForecastInputFixture.of("204000057"));

        // then
        assertThat(actual.distribution().chanceBySeats()).hasSize(71);
    }

    @Test
    void 예보_재료로_낸_좌석_확률을_모두_더하면_1이다() {
        // when
        SeatForecastResult actual = model().predict(ForecastInputFixture.of("204000057"));

        // then
        assertThat(actual.distribution().chanceBySeats().stream()
            .mapToDouble(Double::doubleValue).sum()).isEqualTo(1.0, within(1e-12));
    }

    @Test
    void 계수_묶음이_안_담는_노선이면_예보하지_않는다() {
        // given 목록에 없는 GBIS 노선의 정류장 목록이 들어온다
        assertThat(catchThrowable(() -> model().predict(ForecastInputFixture.of("999999999"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private SeatDistributionForecastModel model() {
        return new SeatDistributionForecastModel(
            LoadedBundle.from(DummyBundle.valid().writeTo(directory)).predictor());
    }
}
