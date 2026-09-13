package com.gustler.backend.processor.seatdistribution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 좌석 분포가 어느 좌석을 가운데로 놓을지 고른다.
 *
 * <p>지금 몇 석 남았는지와 그 차량의 정원만 본다. 여기서 고른 좌석을 가운데로 놓고,
 * 거기서 몇 석 어긋날지를 뒤에서 따로 계산한다.
 *
 * <p>반올림 방식이 학습 쪽과 같아야 한다. 절반에 걸린 값을 늘 올려 버리면 경계에서 한 석씩
 * 어긋나고, 같은 계수로 다른 예보가 나간다.
 */
class SeatAnchorTest {

    @Test
    void 정원_40석에_20석_남은_차량의_중심_좌석은_14석이다() {
        // given 절편 0.1 에 기울기 0.5 인 계수다
        SeatAnchor anchor = new SeatAnchor(0.1, 0.5);

        // when
        final int actual = anchor.seatsOf(20, 40);

        // then (0.1 + 0.5 * 0.5) * 40 = 14
        assertThat(actual).isEqualTo(14);
    }

    @ParameterizedTest
    @CsvSource({"22.5, 22", "23.5, 24"})
    void 중심_좌석이_절반에_걸리면_짝수_쪽으로_반올림한다(
        final double located,
        final int expected
    ) {
        // given 정원 100석에서 절편만으로 이 좌석을 만든다
        SeatAnchor anchor = new SeatAnchor(located / 100.0, 0.0);

        // when
        final int actual = anchor.seatsOf(0, 100);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void 계수가_음수를_내도_중심_좌석은_0석에서_멈춘다() {
        // given
        SeatAnchor anchor = new SeatAnchor(-5.0, 0.0);

        // when
        final int actual = anchor.seatsOf(20, 40);

        // then
        assertThat(actual).isZero();
    }

    @Test
    void 계수가_정원보다_큰_값을_내도_중심_좌석은_정원에서_멈춘다() {
        // given
        SeatAnchor anchor = new SeatAnchor(5.0, 0.0);

        // when
        final int actual = anchor.seatsOf(20, 40);

        // then
        assertThat(actual).isEqualTo(40);
    }

    @Test
    void 정원이_100석인_차량이_와도_중심_좌석은_70석에서_멈춘다() {
        // given 좌석 분포가 0석부터 70석까지만 담는다
        SeatAnchor anchor = new SeatAnchor(1.0, 0.0);

        // when
        final int actual = anchor.seatsOf(0, 100);

        // then
        assertThat(actual).isEqualTo(70);
    }

    @Test
    void 정원을_한_번도_못_본_차량도_중심_좌석이_나온다() {
        // given 정원을 1석 아래로는 안 내려서 0 으로 나누지 않는다
        SeatAnchor anchor = new SeatAnchor(1.0, 0.0);

        // when
        final int actual = anchor.seatsOf(0, 0);

        // then
        assertThat(actual).isEqualTo(1);
    }
}
