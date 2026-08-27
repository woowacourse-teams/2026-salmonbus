package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeatDistributionTest {

    @Test
    void 확률을_모두_더해_1이_되는_분포만_만든다() {
        // when & then
        assertThatCode(() -> SeatDistribution.of(0.2, 0.3, 0.5))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> SeatDistribution.of(0.2, 0.3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY})
    void 좌석_수별_확률은_0과_1_사이의_수다(
        final double notChance
    ) {
        // when & then
        assertThatThrownBy(() -> SeatDistribution.of(notChance, 1.0 - notChance))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 만석_확률은_0석_남을_확률이다() {
        // given
        final SeatDistribution distribution = SeatDistribution.of(0.2, 0.3, 0.5);

        // when
        final double actual = distribution.fullChance();

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
        final double actual = distribution.fullChance();

        // then
        assertThat(actual).isEqualTo(0.996);
    }

    @Test
    void 분포는_한_번_만들어지면_바뀌지_않는다() {
        // given
        final double[] chanceBySeats = {0.2, 0.8};
        final SeatDistribution actual = new SeatDistribution(chanceBySeats);

        // when
        chanceBySeats[0] = 0.9;
        actual.chanceBySeats()[1] = 0.1;

        // then
        assertThat(actual).isEqualTo(SeatDistribution.of(0.2, 0.8));
    }
}
