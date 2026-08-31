package com.gustler.backend.processor.a18;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class FullChanceHurdleTest {

    private static final double TOLERANCE = 1e-15;

    @Test
    void 특징과_계수를_곱해_더한_값을_로지스틱으로_옮긴다() {
        // given
        FullChanceHurdle hurdle = new FullChanceHurdle(new double[] {0.5, 0.25});

        // when
        final double actual = hurdle.rawFullChanceOf(new double[] {1.0, 2.0});

        // then
        assertThat(actual).isEqualTo(1.0 / (1.0 + Math.exp(-1.0)), within(TOLERANCE));
    }

    @Test
    void 곱해_더한_값이_30을_넘어도_확률이_1이_되지_않는다() {
        // given
        FullChanceHurdle hurdle = new FullChanceHurdle(new double[] {1000.0});

        // when
        final double actual = hurdle.rawFullChanceOf(new double[] {1.0});

        // then
        assertThat(actual).isEqualTo(1.0 / (1.0 + Math.exp(-30.0)), within(TOLERANCE));
    }

    @Test
    void 곱해_더한_값이_마이너스_30_아래여도_확률이_0이_되지_않는다() {
        // given
        FullChanceHurdle hurdle = new FullChanceHurdle(new double[] {-1000.0});

        // when
        final double actual = hurdle.rawFullChanceOf(new double[] {1.0});

        // then
        assertThat(actual).isEqualTo(1.0 / (1.0 + Math.exp(30.0)), within(TOLERANCE));
    }

    @Test
    void 특징_개수와_계수_개수가_다르면_확률을_내지_않는다() {
        // given
        FullChanceHurdle hurdle = new FullChanceHurdle(new double[] {1.0, 2.0});

        // when & then
        assertThat(catchThrowable(() -> hurdle.rawFullChanceOf(new double[] {1.0})))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
