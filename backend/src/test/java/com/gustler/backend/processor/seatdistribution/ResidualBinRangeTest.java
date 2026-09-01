package com.gustler.backend.processor.seatdistribution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 중심 좌석에서 몇 석 어긋나는지를 아홉 구간으로 나눈다.
 *
 * <p>구간 경계는 차량마다 다르다. <b>그 방향으로 어긋날 수 있는 폭</b>에 비례해서 잡기 때문이다.
 * 중심이 40석인 차량과 3석인 차량은 "크게 어긋났다" 의 뜻이 다르다.
 *
 * <p>폭이 좁으면 앞 구간들의 경계가 같은 정수로 내려앉아 <b>담을 것이 없는 구간</b>이 생긴다.
 * 그 구간은 확률을 안 받고, 남은 구간끼리 나눠 갖는다.
 */
class ResidualBinRangeTest {

    private static final double[] RELATIVE_EDGES =
        {0.0, 0.03, 0.07, 0.12, 0.2, 0.32, 0.48, 0.7, 1.0};

    /** 정원 70석 차량의 중심 좌석이 3석일 때, 좌석이 더 많아지는 쪽으로 어긋날 수 있는 폭이다. */
    private static final double WIDE = 67.0;

    /** 중심 좌석이 0석이거나 이미 정원까지 찼을 때의 폭이다. */
    private static final double NARROW = 1.0;

    @Test
    void 어긋난_정도를_아홉_구간으로_나눈다() {
        // when
        ResidualBinRange[] actual = ResidualBinRange.allOf(WIDE, RELATIVE_EDGES);

        // then
        assertThat(actual).hasSize(9);
    }

    @Test
    void 첫_구간은_1석_어긋난_것부터_담는다() {
        // when
        ResidualBinRange[] actual = ResidualBinRange.allOf(WIDE, RELATIVE_EDGES);

        // then 0석 어긋난 것은 따로 세므로 구간에 안 들어간다
        assertThat(actual[0].lowest()).isEqualTo(1);
    }

    @Test
    void 어긋날_수_있는_폭이_67석이면_둘째_구간은_4석까지_담는다() {
        // when 0.07 곱하기 67 은 4.69 라 4석에서 끊는다
        ResidualBinRange[] actual = ResidualBinRange.allOf(WIDE, RELATIVE_EDGES);

        // then
        assertThat(actual[1].highest()).isEqualTo(4);
    }

    @Test
    void 마지막_구간은_50석_어긋난_것까지_담는다() {
        // when
        ResidualBinRange[] actual = ResidualBinRange.allOf(NARROW, RELATIVE_EDGES);

        // then
        assertThat(actual[8].highest()).isEqualTo(50);
    }

    @Test
    void 어긋날_수_있는_폭이_67석이면_마지막_구간은_담을_것이_없다() {
        // when 마지막 구간이 68석부터 시작하는데 50석까지만 셀 수 있다
        ResidualBinRange[] actual = ResidualBinRange.allOf(WIDE, RELATIVE_EDGES);

        // then
        assertThat(actual[8].usable()).isFalse();
    }

    @Test
    void 어긋날_수_있는_폭이_1석이면_앞_구간들은_담을_것이_없다() {
        // when 앞 여덟 구간이 전부 1석에서 시작해 0석에서 끝난다
        ResidualBinRange[] actual = ResidualBinRange.allOf(NARROW, RELATIVE_EDGES);

        // then
        assertThat(actual[0].usable()).isFalse();
    }

    @Test
    void 어긋날_수_있는_폭이_1석이면_마지막_구간_하나가_전부를_담는다() {
        // when
        ResidualBinRange[] actual = ResidualBinRange.allOf(NARROW, RELATIVE_EDGES);

        // then
        assertThat(actual[8]).isEqualTo(new ResidualBinRange(2, 50));
    }

    @Test
    void 구간이_5석부터_8석까지면_어긋난_경우_네_가지를_담는다() {
        // when
        ResidualBinRange actual = new ResidualBinRange(5, 8);

        // then
        assertThat(actual.magnitudeCount()).isEqualTo(4);
    }
}
