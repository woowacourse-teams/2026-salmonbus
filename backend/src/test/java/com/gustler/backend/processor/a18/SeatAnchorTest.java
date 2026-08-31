package com.gustler.backend.processor.a18;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SeatAnchorTest {

    @Test
    void 정원과_지금_잔여석_비율로_중심_좌석을_고른다() {
        // given 절편 0.1 · 기울기 0.5 · 정원 40석에 지금 20석
        SeatAnchor anchor = new SeatAnchor(0.1, 0.5);

        // when
        final int actual = anchor.seatsOf(20, 40);

        // then (0.1 + 0.5 * 0.5) * 40 = 14
        assertThat(actual).isEqualTo(14);
    }

    @ParameterizedTest
    @CsvSource({"22.5, 22", "23.5, 24"})
    void 반올림은_0_5_에서_짝수_쪽으로_간다(
        final double located,
        final int expected
    ) {
        // given 정원 100석에서 절편만으로 located 석을 만든다
        SeatAnchor anchor = new SeatAnchor(located / 100.0, 0.0);

        // when
        final int actual = anchor.seatsOf(0, 100);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void 중심_좌석은_0석보다_작아지지_않는다() {
        // given
        SeatAnchor anchor = new SeatAnchor(-5.0, 0.0);

        // when
        final int actual = anchor.seatsOf(20, 40);

        // then
        assertThat(actual).isZero();
    }

    @Test
    void 중심_좌석은_정원을_넘지_않는다() {
        // given
        SeatAnchor anchor = new SeatAnchor(5.0, 0.0);

        // when
        final int actual = anchor.seatsOf(20, 40);

        // then
        assertThat(actual).isEqualTo(40);
    }

    @Test
    void 중심_좌석은_70석을_넘지_않는다() {
        // given 정원이 격자보다 커도 격자에서 멈춘다
        SeatAnchor anchor = new SeatAnchor(1.0, 0.0);

        // when
        final int actual = anchor.seatsOf(0, 100);

        // then
        assertThat(actual).isEqualTo(70);
    }

    @Test
    void 정원_증거가_0이어도_1석을_정원으로_보고_중심_좌석을_낸다() {
        // given 정원을 1 아래로는 안 내려서 0 으로 나누지 않는다
        SeatAnchor anchor = new SeatAnchor(1.0, 0.0);

        // when
        final int actual = anchor.seatsOf(0, 0);

        // then
        assertThat(actual).isEqualTo(1);
    }
}
