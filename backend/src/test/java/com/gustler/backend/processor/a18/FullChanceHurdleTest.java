package com.gustler.backend.processor.a18;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * 그 차량이 목표 정류장에 닿을 때 만석일 확률을 낸다.
 *
 * <p>입력값 하나하나에 계수를 곱해 더하고, 그 값을 0 과 1 사이의 확률로 옮긴다.
 * 옮기기 전에 값을 잘라서 <b>확률이 0퍼센트나 100퍼센트로 굳지 않게</b> 한다.
 * 굳어 버리면 뒤에서 오늘 성적으로 확률을 옮길 때 계산이 무너진다.
 */
class FullChanceHurdleTest {

    private static final double TOLERANCE = 1e-15;

    @Test
    void 입력값과_계수로_만석_확률을_낸다() {
        // given 입력 1 과 2 에 계수 0.5 와 0.25 를 곱해 더하면 1 이다
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
        // given 계수는 둘인데 입력을 하나만 넣는다
        FullChanceHurdle hurdle = new FullChanceHurdle(new double[] {1.0, 2.0});

        // when & then
        assertThat(catchThrowable(() -> hurdle.rawFullChanceOf(new double[] {1.0})))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
