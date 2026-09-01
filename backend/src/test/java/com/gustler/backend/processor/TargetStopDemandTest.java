package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 셀 통계 층이 무엇을 물어보는지 본다.
 *
 * <p>z값을 어떻게 내는지는 셀 통계가 자기 안에서 닫는다. 여기서 보는 것은 <b>대상 정류장과
 * 지나갈 구간이 물음으로 옮겨지는가</b> 하나다.
 */
class TargetStopDemandTest {

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final Instant MORNING_AT = Instant.parse("2026-08-19T08:30:00+09:00");

    private static final int PASSED_STOP_44 = 44;
    private static final int TARGET_STOP_49 = 49;
    private static final int CROWD_LEVEL_3 = 3;
    private static final int SEATS_LEFT = 12;

    @Test
    void 셀이_있는_정류장은_그_세대_안에서_잰_값이_나온다() {
        // given 49번 셀이 45번보다 더 차 있다
        StopDemandStatistics statistics = statisticsOf(
            new StopDemandCell(45, 0.2, 0.1, 30, 10),
            new StopDemandCell(TARGET_STOP_49, 0.8, 0.1, 30, 10));

        // when
        TargetStopDemand actual = TargetStopDemand.of(statistics, target());

        // then
        assertThat(actual.fillRateScore()).isPositive();
    }

    @Test
    void 셀이_없는_정류장은_이웃으로_메웠다고_답한다() {
        // given 대상인 49번 셀이 없다
        StopDemandStatistics statistics = statisticsOf(new StopDemandCell(47, 0.2, 0.1, 30, 10));

        // when
        TargetStopDemand actual = TargetStopDemand.of(statistics, target());

        // then
        assertThat(actual.filledByNeighbours()).isTrue();
    }

    @Test
    void 순승차는_대상까지_지나갈_구간을_합쳐_읽는다() {
        // given 45번부터 49번까지가 이 차량이 지나갈 구간이고 그 구간만 순승차가 크다
        StopDemandStatistics statistics = statisticsOf(
            new StopDemandCell(40, 0.5, -0.2, 30, 10),
            new StopDemandCell(45, 0.5, 0.3, 30, 10),
            new StopDemandCell(TARGET_STOP_49, 0.5, 0.3, 30, 10));

        // when
        TargetStopDemand actual = TargetStopDemand.of(statistics, target());

        // then
        assertThat(actual.netBoardingSegmentScore()).isPositive();
    }

    private static VehicleStopTarget target() {
        return new VehicleStopTarget(
            new ObservedVehicle(
                "204000206", ROUTE_VERSION_3330, PASSED_STOP_44, MORNING_AT, SEATS_LEFT, CROWD_LEVEL_3),
            new RouteStop(ROUTE_VERSION_3330, TARGET_STOP_49, "20400049", true));
    }

    private static StopDemandStatistics statisticsOf(
        StopDemandCell... cells
    ) {
        return new StopDemandStatistics(ROUTE_VERSION_3330, TimeSlot.MORNING, 1, List.of(cells));
    }
}
