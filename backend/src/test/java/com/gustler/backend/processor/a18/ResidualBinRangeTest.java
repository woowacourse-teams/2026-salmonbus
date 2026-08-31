package com.gustler.backend.processor.a18;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResidualBinRangeTest {

    private static final double[] RELATIVE_EDGES =
        {0.0, 0.03, 0.07, 0.12, 0.2, 0.32, 0.48, 0.7, 1.0};

    @Test
    void 묶음은_아홉_개다() {
        // when
        ResidualBinRange[] actual = ResidualBinRange.allOf(67.0, RELATIVE_EDGES);

        // then
        assertThat(actual).hasSize(9);
    }

    @Test
    void 첫_묶음은_잔차_1석부터_시작한다() {
        // when
        ResidualBinRange[] actual = ResidualBinRange.allOf(67.0, RELATIVE_EDGES);

        // then
        assertThat(actual[0].lowest()).isEqualTo(1);
    }

    @Test
    void 묶음의_위_끝은_다음_상대_경계에_배율을_곱해_내린_값이다() {
        // when 0.07 * 67 = 4.69 라 4 석이다
        ResidualBinRange[] actual = ResidualBinRange.allOf(67.0, RELATIVE_EDGES);

        // then
        assertThat(actual[1].highest()).isEqualTo(4);
    }

    @Test
    void 마지막_묶음의_위_끝은_잔차_격자의_끝인_50석이다() {
        // when
        ResidualBinRange[] actual = ResidualBinRange.allOf(67.0, RELATIVE_EDGES);

        // then
        assertThat(actual[8].highest()).isEqualTo(50);
    }

    @Test
    void 배율이_커서_시작이_50석을_넘으면_담을_잔차가_없다() {
        // when 배율 67 이면 마지막 묶음이 68석부터인데 격자가 50석에서 끝난다
        ResidualBinRange[] actual = ResidualBinRange.allOf(67.0, RELATIVE_EDGES);

        // then
        assertThat(actual[8].usable()).isFalse();
    }

    @Test
    void 배율이_작으면_경계가_겹쳐_담을_잔차가_없는_묶음이_생긴다() {
        // when 배율 1 이면 앞의 여덟 묶음이 전부 1석에서 시작해 0석에서 끝난다
        ResidualBinRange[] actual = ResidualBinRange.allOf(1.0, RELATIVE_EDGES);

        // then
        assertThat(actual[0].usable()).isFalse();
    }

    @Test
    void 배율이_1이면_마지막_묶음_하나가_잔차_전부를_담는다() {
        // when
        ResidualBinRange[] actual = ResidualBinRange.allOf(1.0, RELATIVE_EDGES);

        // then
        assertThat(actual[8]).isEqualTo(new ResidualBinRange(2, 50));
    }

    @Test
    void 담을_잔차_수는_시작과_끝을_모두_센다() {
        // when
        ResidualBinRange actual = new ResidualBinRange(5, 8);

        // then
        assertThat(actual.magnitudeCount()).isEqualTo(4);
    }
}
