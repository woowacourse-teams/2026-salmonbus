package com.gustler.backend.processor.a18;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 파이썬 정본과 같은 좌석 분포를 내는지 잰다.
 *
 * <p>이 테스트가 이 티켓에서 제일 값나가는 자리다. 나머지 테스트는 내가 이해한 규칙을 내가 다시
 * 확인하는 것이라 오해가 있으면 오해째로 통과한다. 이것만 밖에서 온 값과 견준다.
 */
class A18ReferenceParityTest {

    private static final double TOLERANCE = 1e-12;

    /** 잔차 격자의 아래 끝. 중심 좌석에서 이만큼 빼면 격자 밖 질량이 쌓이는 좌석 칸이다. */
    private static final int SMALLEST_RESIDUAL_SEATS = -40;

    static Stream<ReferenceParityCase> 대조_사례() {
        return ReferenceParityCase.all().stream()
            .filter(one -> !ReferenceParityCase.DIVERGING_CASES.contains(one.name()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("대조_사례")
    void 좌석_71칸의_확률이_파이썬_정본과_같다(
        ReferenceParityCase given
    ) {
        // when
        double[] actual = given.seatChances();

        // then
        assertThat(actual).containsExactly(given.expectedProbabilities(), within(TOLERANCE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("대조_사례")
    void 중심_좌석이_파이썬_정본과_같다(
        ReferenceParityCase given
    ) {
        // when
        final int actual = new SeatAnchor(given.anchorIntercept(), given.anchorSlope())
            .seatsOf(given.upstreamSeats(), given.capacity());

        // then
        assertThat(actual).isEqualTo(given.anchorSeats());
    }

    @Test
    void 대조_사례는_여덟_개다() {
        // when
        List<ReferenceParityCase> actual = ReferenceParityCase.all();

        // then
        assertThat(actual).hasSize(8);
    }

    @Test
    void 만석이_아닐_확률이_한_칸도_안_남으면_파이썬_채점기는_만석을_100퍼센트로_만든다() {
        // given 서빙 계약과 갈리는 자리라 대조에서 뺀 사례다
        ReferenceParityCase given =
            ReferenceParityCase.named(ReferenceParityCase.NO_SEAT_LEFT_TO_SPREAD);

        // when
        final double actual = given.expectedProbabilities()[0];

        // then
        assertThat(actual).isEqualTo(1.0);
    }

    @Test
    void 만석이_아닐_확률이_한_칸도_안_남으면_우리는_탈_수_있는_가장_가까운_좌석에_남긴다() {
        // given
        ReferenceParityCase given =
            ReferenceParityCase.named(ReferenceParityCase.NO_SEAT_LEFT_TO_SPREAD);

        // when
        double[] actual = given.seatChances();

        // then
        assertThat(actual[1]).isEqualTo(1.0 - given.fullChance(), within(TOLERANCE));
    }

    /**
     * 정원 70석에 중심 3석이면 좌석이 늘어나는 쪽 배율이 67 이고, 크기 47~50 묶음이 통째로
     * 잔차 격자(-40) 밖으로 밀린다. 파이썬 채점기는 그 묶음의 질량을 2.75배로 놓는다.
     * 격자에 걸치기만 하는 묶음(크기 33~46)에서는 정확히 1배로 놓아서, 통째로 밀릴 때만
     * 어긋난다. 우리는 밀린 크기 하나에 한 몫씩만 놓는다.
     */
    @Test
    void 구간_전체가_셀_수_있는_범위_밖이면_파이썬_채점기가_가장자리에_확률을_더_놓는다() {
        // given 중심 3석에서 잔차 -40 은 43석 칸이다
        ReferenceParityCase given =
            ReferenceParityCase.named(ReferenceParityCase.BIN_FULLY_UNDER_GRID);
        final int edgeSeats = given.anchorSeats() - SMALLEST_RESIDUAL_SEATS;

        // when
        double[] actual = given.seatChances();

        // then
        assertThat(actual[edgeSeats]).isLessThan(given.expectedProbabilities()[edgeSeats]);
    }

    @Test
    void 구간_전체가_셀_수_있는_범위_밖이어도_확률의_합은_1이다() {
        // given
        ReferenceParityCase given =
            ReferenceParityCase.named(ReferenceParityCase.BIN_FULLY_UNDER_GRID);

        // when
        final double actual = Arrays.stream(given.seatChances()).sum();

        // then
        assertThat(actual).isEqualTo(1.0, within(TOLERANCE));
    }
}
