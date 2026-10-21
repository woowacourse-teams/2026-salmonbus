package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ResidualDistributionTest {
    private static final double TOLERANCE = 1e-12;
    private static final int NO_RESIDUAL = SeatGrid.residualIndexOf(0);

    /**
     * 아홉 묶음에 모두 잔차가 포함되는 조건. 중심 35석 · 정원 70석이면 양쪽 배율이 모두 35 다.
     * 배율이 34 보다 작으면 앞 묶음들의 내림값이 같아져 빈 묶음이 생기고, 49 보다 크면
     * 마지막 묶음이 잔차 격자 50 밖에서 시작한다.
     */
    private static final int ALL_BINS_USABLE_ANCHOR = 35;

    private static final int ALL_BINS_USABLE_CAPACITY = 70;

    @Test
    void 어긋날_수_있는_경우를_91가지로_센다() {
        // when
        double[] actual = allFitted().chancesByResidual(CoefficientsFixture.ONE_FEATURE, 20, 44);

        // then
        assertThat(actual).hasSize(91);
    }

    @Test
    void 확률을_모두_더하면_1이다() {
        // when
        double[] actual = allFitted().chancesByResidual(CoefficientsFixture.ONE_FEATURE, 20, 44);

        // then
        assertThat(Arrays.stream(actual)
            .sum()).isEqualTo(1.0, within(TOLERANCE));
    }

    @Test
    void 양쪽_방향_다_학습이_안_됐으면_확률이_전부_중심_좌석에_모인다() {
        // given
        ResidualDistribution distribution = CoefficientsFixture.residuals(
            0.2, 0.5, CoefficientsFixture.noneFitted(), CoefficientsFixture.noneFitted());

        // when
        double[] actual = distribution.chancesByResidual(CoefficientsFixture.ONE_FEATURE, 20, 44);

        // then
        assertThat(actual[NO_RESIDUAL]).isEqualTo(1.0, within(TOLERANCE));
    }

    @Test
    void 좌석이_중심보다_많아지는_쪽이_학습이_안_됐으면_그_확률이_중심_좌석으로_간다() {
        // given
        // 같을 확률 0.2 · 중심보다 적을 확률 0.5 이므로 많을 확률이 0.3 이다
        ResidualDistribution distribution = CoefficientsFixture.residuals(
            0.2, 0.5, CoefficientsFixture.allFitted(), CoefficientsFixture.noneFitted());

        // when
        double[] actual = distribution.chancesByResidual(
            CoefficientsFixture.ONE_FEATURE, ALL_BINS_USABLE_ANCHOR, ALL_BINS_USABLE_CAPACITY);

        // then
        // 0.2 + 0.3 이 잔차 0 에 모인다
        assertThat(actual[NO_RESIDUAL]).isEqualTo(0.5, within(TOLERANCE));
    }

    @Test
    void 좌석이_중심보다_많아질_확률은_나머지_둘을_1에서_뺀_값이다() {
        // given
        ResidualDistribution distribution = CoefficientsFixture.residuals(
            0.2, 0.5, CoefficientsFixture.allFitted(), CoefficientsFixture.allFitted());

        // when
        double[] chances = distribution.chancesByResidual(
            CoefficientsFixture.ONE_FEATURE, ALL_BINS_USABLE_ANCHOR, ALL_BINS_USABLE_CAPACITY);
        final double actual = Arrays.stream(chances, 0, NO_RESIDUAL)
            .sum();

        // then
        assertThat(actual).isEqualTo(0.3, within(TOLERANCE));
    }

    /**
     * 중심 20석이면 첫 묶음의 범위는 잔차 1석부터 0석까지여서 포함할 잔차가 없다.
     * 이 묶음의 확률은 남은 여덟 묶음에 재분배하지 않고 중심 좌석으로 돌린다.
     */
    @Test
    void 담을_것이_없는_구간의_몫은_남은_칸들이_나눠_받는다() {
        // given
        // 늘어나는 방향이 미적합이므로 잔차 0 에 0.2 더하기 0.3 이 배분되어야 한다
        ResidualDistribution distribution = CoefficientsFixture.residuals(
            0.2, 0.5, CoefficientsFixture.allFitted(), CoefficientsFixture.noneFitted());

        // when
        double[] actual = distribution.chancesByResidual(CoefficientsFixture.ONE_FEATURE, 20, 44);

        // then
        // 줄어드는 방향에서 아홉 중 하나의 질량을 배분하지 못해 중심 좌석의 확률이 그보다 커진다
        assertThat(actual[NO_RESIDUAL]).isGreaterThan(0.5);
    }

    @Test
    void 앞의_두_확률을_더해_1을_넘어도_많아질_확률이_0이_되지_않는다() {
        // given
        // 둘을 더하면 1.8 이라 나머지가 음수가 된다
        ResidualDistribution distribution = CoefficientsFixture.residuals(
            0.9, 0.9, CoefficientsFixture.allFitted(), CoefficientsFixture.allFitted());

        // when
        double[] chances = distribution.chancesByResidual(CoefficientsFixture.ONE_FEATURE, 20, 44);
        final double actual = Arrays.stream(chances, 0, NO_RESIDUAL)
            .sum();

        // then
        assertThat(actual).isGreaterThan(0.0);
    }

    @Test
    void 한_구간의_확률은_그_구간이_담는_좌석_수에_고르게_나뉜다() {
        // given
        // 중심 67석이면 첫 묶음이 잔차 1석과 2석 둘을 담는다
        ResidualDistribution distribution = CoefficientsFixture.residuals(
            0.2, 0.5, CoefficientsFixture.onlyFirstFitted(), CoefficientsFixture.noneFitted());

        // when
        double[] actual = distribution.chancesByResidual(CoefficientsFixture.ONE_FEATURE, 67, 70);

        // then
        assertThat(actual[SeatGrid.residualIndexOf(1)])
            .isEqualTo(actual[SeatGrid.residualIndexOf(2)], within(TOLERANCE));
    }

    @Test
    void 학습이_안_된_구간도_아주_작은_확률을_받는다() {
        // given
        // 첫 묶음만 적합됐는데 나머지 묶음도 담는 잔차가 있다
        ResidualDistribution distribution = CoefficientsFixture.residuals(
            0.2, 0.5, CoefficientsFixture.onlyFirstFitted(), CoefficientsFixture.noneFitted());

        // when
        // 잔차 3석은 둘째 묶음이 담는다
        double[] actual = distribution.chancesByResidual(CoefficientsFixture.ONE_FEATURE, 67, 70);

        // then
        assertThat(actual[SeatGrid.residualIndexOf(3)]).isGreaterThan(0.0);
    }

    @Test
    void 셀_수_있는_범위_밖으로_밀린_확률은_가장자리_칸에_쌓인다() {
        // given
        // 정원 70석에 중심 3석이면 늘어나는 쪽 배율이 67 이라 크기 41석부터가 격자 밖이다
        ResidualDistribution distribution = CoefficientsFixture.residuals(
            0.2, 0.5, CoefficientsFixture.noneFitted(), CoefficientsFixture.allFitted());

        // when
        double[] actual = distribution.chancesByResidual(CoefficientsFixture.ONE_FEATURE, 3, 70);

        // then
        // 가장자리 칸의 확률이 바로 옆 칸보다 크다
        assertThat(actual[0]).isGreaterThan(actual[1]);
    }

    private ResidualDistribution allFitted() {
        return CoefficientsFixture.residuals(
            0.2, 0.5, CoefficientsFixture.allFitted(), CoefficientsFixture.allFitted());
    }
}
