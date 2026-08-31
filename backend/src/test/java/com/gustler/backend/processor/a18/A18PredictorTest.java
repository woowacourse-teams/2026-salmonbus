package com.gustler.backend.processor.a18;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.processor.SeatForecastResult;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class A18PredictorTest {

    private static final double TOLERANCE = 1e-12;
    private static final String ROUTE = "3330";
    private static final int FOUR_STOPS_AHEAD = 4;

    @Test
    void 좌석_분포는_71칸이다() {
        // when
        SeatForecastResult actual = predictor().predict(input(20, 44, null));

        // then
        assertThat(actual.distribution().chanceBySeats()).hasSize(71);
    }

    @Test
    void 모든_칸이_유한하다() {
        // when
        SeatForecastResult actual = predictor().predict(input(20, 44, null));

        // then
        assertThat(actual.distribution().chanceBySeats()).allMatch(Double::isFinite);
    }

    @Test
    void 확률을_모두_더하면_1이다() {
        // when
        SeatForecastResult actual = predictor().predict(input(20, 44, null));

        // then
        assertThat(actual.distribution().chanceBySeats().stream().mapToDouble(Double::doubleValue).sum())
            .isEqualTo(1.0, within(TOLERANCE));
    }

    /**
     * 잔여석이 0석일 확률로 들어가는 것은 허들이 낸 원값이 아니라 당일 성적으로 옮긴 값이다.
     * 기대값은 정본 6.2절 식을 이 테스트에서 따로 계산해 둔다. 구현을 불러 견주면
     * 구현이 틀려도 같은 값이 나와서 아무것도 못 잰다.
     */
    @Test
    void 잔여석이_0석일_확률은_당일_성적으로_옮긴_값이다() {
        // given 확정된 예보 100건 중 90건이 만석이었는데 예보 평균은 10퍼센트였다
        SameDayFullOutcomes outcomes = new SameDayFullOutcomes(100, 90, 0.1);
        final double target = (90 + 200 * 0.1) / (100 + 200);
        final double shift = logitOf(target) - logitOf(0.1);
        final double expected = 1.0 / (1.0 + Math.exp(-(logitOf(0.2) + shift)));

        // when
        SeatForecastResult actual = predictor().predict(input(20, 44, outcomes));

        // then
        assertThat(actual.distribution().fullChance()).isEqualTo(expected, within(TOLERANCE));
    }

    @Test
    void 당일_성적이_없으면_잔여석이_0석일_확률은_허들이_낸_원값이다() {
        // when
        SeatForecastResult actual = predictor().predict(input(20, 44, null));

        // then
        assertThat(actual.distribution().fullChance()).isEqualTo(0.2, within(TOLERANCE));
    }

    private static double logitOf(
        final double chance
    ) {
        return Math.log(chance / (1.0 - chance));
    }

    @Test
    void 기대_잔여석은_좌석과_확률을_곱해_더한_값이다() {
        // when
        SeatForecastResult actual = predictor().predict(input(20, 44, null));

        // then
        assertThat(actual.distribution().expectedSeats()).isEqualTo(
            IntStream.range(0, 71)
                .mapToDouble(seats -> seats * actual.distribution().chanceOf(seats))
                .sum(),
            within(TOLERANCE));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 44, 69, 70})
    void 정원이_1석부터_70석까지_어디여도_분포가_나온다(
        final int capacity
    ) {
        // when
        SeatForecastResult actual = predictor().predict(input(Math.min(20, capacity), capacity, null));

        // then
        assertThat(actual.distribution().chanceBySeats().stream().mapToDouble(Double::doubleValue).sum())
            .isEqualTo(1.0, within(TOLERANCE));
    }

    @Test
    void 정원보다_많은_좌석의_확률은_0이다() {
        // when
        SeatForecastResult actual = predictor().predict(input(10, 30, null));

        // then
        assertThat(actual.distribution().chanceBySeats().subList(31, 71))
            .allMatch(chance -> chance == 0.0);
    }

    @Test
    void 번들이_요구하는_특징_개수와_벡터_길이가_다르면_예보하지_않는다() {
        // given 계수는 한 칸인데 특징을 두 칸 넣는다
        A18PredictionInput given = new A18PredictionInput(
            new double[] {1.0, 1.0}, ROUTE, FOUR_STOPS_AHEAD, 20, 44, null);

        // when & then
        assertThat(catchThrowable(() -> predictor().predict(given)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잔여석을_정원보다_많이_넣어도_분포가_나온다() {
        // given 정원 증거가 잔여석보다 작게 잡힌 관측이 실제로 온다
        SeatForecastResult actual = predictor().predict(input(30, 20, null));

        // then
        assertThat(actual.distribution().chanceBySeats().stream().mapToDouble(Double::doubleValue).sum())
            .isEqualTo(1.0, within(TOLERANCE));
    }

    private A18Predictor predictor() {
        return new A18Predictor(
            CoefficientsFixture.lookupOf(CoefficientsFixture.horizon(0.2, 0.05, 0.8, 0.2, 0.5)),
            CoefficientsFixture.RELATIVE_EDGES);
    }

    private A18PredictionInput input(
        final int currentSeats,
        final int capacity,
        SameDayFullOutcomes outcomes
    ) {
        return new A18PredictionInput(
            CoefficientsFixture.ONE_FEATURE, ROUTE, FOUR_STOPS_AHEAD, currentSeats, capacity, outcomes);
    }
}
