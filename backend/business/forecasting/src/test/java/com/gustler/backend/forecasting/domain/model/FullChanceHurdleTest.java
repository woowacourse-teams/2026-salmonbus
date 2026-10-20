package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * 차량이 목표 정류장에 도착할 때 만석일 확률을 계산한다.
 *
 * <p>각 입력값에 계수를 곱해 더하고 0 과 1 사이의 확률로 변환한다.
 * 변환 전에 값을 제한해 확률이 0퍼센트나 100퍼센트로 포화되지 않도록 한다.
 * 포화되면 당일 성적으로 확률을 보정할 때 계산이 성립하지 않는다.
 */
class FullChanceHurdleTest {
    private static final double TOLERANCE = 1e-15;

    @Test
    void 입력값과_계수로_만석_확률을_낸다() {
        // given
        // 입력 1 과 2 에 계수 0.5 와 0.25 를 곱해 더하면 1 이다
        FullChanceHurdle hurdle = new FullChanceHurdle(new double[] {0.5, 0.25});

        // when
        final double actual = hurdle.rawFullChanceOf(new double[] {1.0, 2.0});

        // then
        assertThat(actual).isEqualTo(1.0 / (1.0 + Math.exp(-1.0)), within(TOLERANCE));
    }

    @Test
    void 입력이_아무리_커도_만석_확률이_100퍼센트가_되지_않는다() {
        // given
        FullChanceHurdle hurdle = new FullChanceHurdle(new double[] {1000.0});

        // when
        final double actual = hurdle.rawFullChanceOf(new double[] {1.0});

        // then
        assertThat(actual).isLessThan(1.0);
    }

    @Test
    void 입력이_아무리_작아도_만석_확률이_0퍼센트가_되지_않는다() {
        // given
        FullChanceHurdle hurdle = new FullChanceHurdle(new double[] {-1000.0});

        // when
        final double actual = hurdle.rawFullChanceOf(new double[] {1.0});

        // then
        assertThat(actual).isGreaterThan(0.0);
    }

    @Test
    void 입력값_개수가_계수_개수와_다르면_예보하지_않는다() {
        // given
        // 계수는 둘인데 입력을 하나만 넣는다
        FullChanceHurdle hurdle = new FullChanceHurdle(new double[] {1.0, 2.0});

        // when
        Throwable caughtFailure = catchThrowable(() -> hurdle.rawFullChanceOf(new double[] {1.0}));

        // then
        assertThat(caughtFailure)
            .isInstanceOf(IllegalArgumentException.class);
    }
}
