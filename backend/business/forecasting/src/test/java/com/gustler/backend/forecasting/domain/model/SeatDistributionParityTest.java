package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 파이썬 정본과 같은 좌석 분포를 계산하는지 검증한다.
 *
 * <p>이 티켓에서 특히 중요한 대조 테스트다. 다른 테스트는 해석한 규칙을 다시 확인하므로
 * 잘못된 해석을 공유해도 통과할 수 있다. 이 테스트는 외부 구현에서 생성한 값과 직접 대조한다.
 */
class SeatDistributionParityTest {
    private static final double TOLERANCE = 1e-12;

    /** 잔차 격자의 아래 끝. 중심 좌석에서 이만큼 빼면 격자 밖 질량이 쌓이는 좌석 칸이다. */
    private static final int SMALLEST_RESIDUAL_SEATS = -40;

    static Stream<ReferenceParityCase> 대조_사례() {
        return ReferenceParityCase.all()
            .stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("대조_사례")
    void 좌석_71칸의_확률이_파이썬_정본과_같다(ReferenceParityCase given) {
        // when
        double[] actual = given.seatChances();

        // then
        assertThat(actual).containsExactly(given.expectedProbabilities(), within(TOLERANCE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("대조_사례")
    void 중심_좌석이_파이썬_정본과_같다(ReferenceParityCase given) {
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
}
