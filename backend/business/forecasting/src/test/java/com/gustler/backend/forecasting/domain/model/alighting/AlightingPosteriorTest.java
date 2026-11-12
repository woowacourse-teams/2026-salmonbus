package com.gustler.backend.forecasting.domain.model.alighting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AlightingPosteriorTest {
    @Test
    void 원본_Python_식을_독립_계산한_만석_posterior와_같다() {
        assertThat(calculate(2, 0, 40, 5, .12, 2, 1.5, 1)).isCloseTo(6.086356103991294, within(1e-10));
        assertThat(calculate(0, 0, 40, 2, .25, 3, .7, 1)).isCloseTo(6.309986598318361, within(1e-10));
    }

    @Test
    void 좌석이_남은_정확한_승차수와_빈차_경계를_계산한다() {
        assertThat(calculate(2, 5, 40, 5, .12, 2, 1.5, 1)).isCloseTo(7.384762030859097, within(1e-10));
        assertThat(calculate(40, 38, 40, 8, .2, 1, 1, 0)).isEqualTo(40);
    }

    @Test
    void 잘못된_용량을_추정값으로_숨기지_않는다() {
        assertThatThrownBy(() -> calculate(41, 0, 40, 5, .12, 2, 1.5, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정원_44석과_45석의_Python_기대값과_같다() {
        double[] lambda = new double[8];
        Arrays.fill(lambda, 2);
        AlightingPosterior.Parameters parameters = new AlightingPosterior.Parameters(lambda, .2, 1, 2);

        assertThat(AlightingPosterior.afterSeats(parameters, 10, 5, 44, 5, false, 3))
            .isCloseTo(16.27113304835795, within(1e-12));
        assertThat(AlightingPosterior.afterSeats(parameters, 10, 5, 45, 5, false, 3))
            .isCloseTo(16.449546403813994, within(1e-12));
    }

    private double calculate(
        final int arrivalSeats,
        final int departureSeats,
        final int capacity,
        final double gap,
        final double alightingProbability,
        final double dispersion,
        final double boardingRate,
        final double fullBusMultiplier
    ) {
        double[] lambda = new double[8];
        Arrays.fill(lambda, boardingRate);
        AlightingPosterior.Parameters parameters = new AlightingPosterior.Parameters(
            lambda, alightingProbability, fullBusMultiplier, dispersion);
        return AlightingPosterior.afterSeats(parameters, arrivalSeats, departureSeats, capacity, gap, true, 0);
    }
}
