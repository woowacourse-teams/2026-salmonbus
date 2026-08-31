package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 지금 계약이 A18 을 담을 수 있는지 코드로 판정한다.
 *
 * <p>이 테스트가 깨지면 둘 중 하나다. 계약이 넓어졌거나, 설계행렬이 바뀌었거나.
 * 어느 쪽이든 판정을 다시 해야 한다.
 */
class A18DesignMatrixProbeTest {

    private static final Clock KOREAN_CLOCK =
        Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final Instant MORNING_AT = Instant.parse("2026-08-19T08:30:00+09:00");

    @Test
    void 설계행렬_31열_중_7열만_지금_계약으로_채워진다() {
        // when
        double[] actual = probe().designMatrixOf(target());

        // then
        assertThat(A18DesignMatrixProbe.unreachableColumnNumbers(actual)).hasSize(24);
    }

    @Test
    void 채울_수_있는_것은_상수와_시간대와_잔여석과_순번뿐이다() {
        // when
        double[] actual = probe().designMatrixOf(target());

        // then
        assertThat(A18DesignMatrixProbe.unreachableColumnNumbers(actual))
            .doesNotContain(1, 2, 3, 4, 6, 7, 20);
    }

    @Test
    void 정원으로_나눈_두_열은_못_채운다() {
        // when
        double[] actual = probe().designMatrixOf(target());

        // then
        assertThat(A18DesignMatrixProbe.unreachableColumnNumbers(actual)).contains(5, 12);
    }

    @Test
    void 궤적이_이미_꺼내_놓은_여섯_열도_못_채운다() {
        // when
        double[] actual = probe().designMatrixOf(target());

        // then
        assertThat(A18DesignMatrixProbe.unreachableColumnNumbers(actual)).contains(13, 14, 15, 16, 17, 18);
    }

    @Test
    void 셀_통계_세_열도_못_채운다() {
        // when
        double[] actual = probe().designMatrixOf(target());

        // then
        assertThat(A18DesignMatrixProbe.unreachableColumnNumbers(actual)).contains(29, 30, 31);
    }

    @Test
    void 노선_정류장_수를_몰라_위치_기저_여덟_열을_못_채운다() {
        // when
        double[] actual = probe().designMatrixOf(target());

        // then
        assertThat(A18DesignMatrixProbe.unreachableColumnNumbers(actual))
            .contains(21, 22, 23, 24, 25, 26, 27, 28);
    }

    @Test
    void 가짜_구현이라도_계약에_끼워지면_예보는_나온다() {
        // when
        SeatForecastResult actual = probe().predict(target());

        // then
        assertThat(actual.fullChance()).isEqualTo(0.4);
    }

    private A18DesignMatrixProbe probe() {
        return new A18DesignMatrixProbe(KOREAN_CLOCK);
    }

    private VehicleStopTarget target() {
        return new VehicleStopTarget(
            new ObservedVehicle("204000206", ROUTE_VERSION_3330, 44, MORNING_AT, 12),
            new RouteStop(ROUTE_VERSION_3330, 49, "20400049", true));
    }
}
