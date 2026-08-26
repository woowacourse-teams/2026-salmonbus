package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeatDistributionTest {

    @Test
    void 좌석_수별_확률을_모두_더하면_1이다() {
        // when
        final SeatDistribution actual = SeatDistribution.of(0.2, 0.3, 0.5);

        // then
        assertThat(actual.probabilities()).containsExactly(0.2, 0.3, 0.5);
        assertThatThrownBy(() -> SeatDistribution.of(0.2, 0.3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY})
    void 좌석_수별_확률은_0과_1_사이의_수다(
        final double notProbability
    ) {
        // when & then
        assertThatThrownBy(() -> SeatDistribution.of(notProbability, 1.0 - notProbability))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 만석_확률은_0석_남을_확률이다() {
        // given
        final SeatDistribution distribution = SeatDistribution.of(0.2, 0.3, 0.5);

        // when
        final double actual = distribution.pFull();

        // then
        assertThat(actual).isEqualTo(0.2);
    }

    @Test
    void 좌석이_2석_남을_확률이_100퍼센트면_기대_잔여석도_2석이다() {
        // given
        final SeatDistribution distribution = SeatDistribution.of(0.0, 0.0, 1.0);

        // when
        final double actual = distribution.expectedSeats();

        // then
        assertThat(actual).isEqualTo(2.0);
    }

    @Test
    void 좌석이_0석과_2석일_확률이_각각_50퍼센트면_기대_잔여석은_1석이다() {
        // given
        final SeatDistribution distribution = SeatDistribution.of(0.5, 0.0, 0.5);

        // when
        final double actual = distribution.expectedSeats();

        // then
        assertThat(actual).isEqualTo(1.0);
    }

    @Test
    void 분포에_넣은_확률이_그대로_나온다() {
        // given
        final SeatDistribution distribution = SeatDistribution.of(0.996, 0.004);

        // when
        final double actual = distribution.pFull();

        // then
        assertThat(actual).isEqualTo(0.996);
    }

    @Test
    void 분포는_한_번_만들어지면_바뀌지_않는다() {
        // given
        final double[] probabilities = {0.2, 0.8};
        final SeatDistribution actual = new SeatDistribution(probabilities);

        // when
        probabilities[0] = 0.9;
        actual.probabilities()[1] = 0.1;

        // then
        assertThat(actual).isEqualTo(SeatDistribution.of(0.2, 0.8));
    }
}
