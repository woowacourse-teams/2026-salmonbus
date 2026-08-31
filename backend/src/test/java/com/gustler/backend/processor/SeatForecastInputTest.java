package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 모델이 받는 재료 묶음이 손으로 만들어지는지, 어긋난 재료를 거르는지 본다.
 *
 * <p>손으로 만들어진다는 것이 곧 백테스트가 된다는 뜻이다. 과거 시점 재료를 이렇게 넣으면
 * 그때의 예보가 그대로 다시 나온다. DB 도 배치도 필요 없다.
 */
class SeatForecastInputTest {

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final long ROUTE_VERSION_1650 = 2L;
    private static final long ANY_OBSERVATION_ID = 7L;

    private static final Instant MORNING_AT = Instant.parse("2026-08-19T08:30:00+09:00");

    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";

    private static final int PASSED_STOP_44 = 44;
    private static final int TARGET_STOP_49 = 49;
    private static final int LAST_STOP_60 = 60;

    private static final int SEATS_LEFT = 12;
    private static final int MAXIMUM_SEATS_EVER_OBSERVED = 44;
    private static final int CROWD_LEVEL_3 = 3;

    @Test
    void 손으로_만든_재료_넷만으로_모델_입력이_선다() {
        // when
        SeatForecastInput actual = new SeatForecastInput(
            target(), trajectoryOf(observed(VEHICLE_204000206)), statisticsOf(ROUTE_VERSION_3330), stops());

        // then
        assertThat(actual.maximumSeatsEverObserved()).isEqualTo(MAXIMUM_SEATS_EVER_OBSERVED);
    }

    @Test
    void 궤적이_다른_차량의_것이면_모델_입력을_만들_수_없다() {
        // given
        VehicleTrajectory otherVehicle = trajectoryOf(observed(VEHICLE_204003542));

        // when, then
        assertThatThrownBy(() -> new SeatForecastInput(
            target(), otherVehicle, statisticsOf(ROUTE_VERSION_3330), stops()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 셀_통계가_다른_노선_판본의_것이면_모델_입력을_만들_수_없다() {
        // given
        StopDemandStatistics otherVersion = statisticsOf(ROUTE_VERSION_1650);

        // when, then
        assertThatThrownBy(() -> new SeatForecastInput(
            target(), trajectoryOf(observed(VEHICLE_204000206)), otherVersion, stops()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정류장_목록이_다른_노선_판본의_것이면_모델_입력을_만들_수_없다() {
        // given
        RouteStops otherVersion = new RouteStops(
            ROUTE_VERSION_1650, List.of(new RouteStop(ROUTE_VERSION_1650, TARGET_STOP_49, "20400049", true)));

        // when, then
        assertThatThrownBy(() -> new SeatForecastInput(
            target(), trajectoryOf(observed(VEHICLE_204000206)), statisticsOf(ROUTE_VERSION_3330), otherVersion))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static VehicleStopTarget target() {
        return new VehicleStopTarget(
            observed(VEHICLE_204000206), new RouteStop(ROUTE_VERSION_3330, TARGET_STOP_49, "20400049", true));
    }

    private static ObservedVehicle observed(
        String vehicleId
    ) {
        return new ObservedVehicle(
            vehicleId, ROUTE_VERSION_3330, PASSED_STOP_44, MORNING_AT, SEATS_LEFT, CROWD_LEVEL_3);
    }

    private static VehicleTrajectory trajectoryOf(
        ObservedVehicle observation
    ) {
        return new VehicleTrajectory(
            ANY_OBSERVATION_ID,
            observation,
            new ObservedSeats.Known(SEATS_LEFT),
            new SeatSlope.Known(-3),
            new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD),
            new FullSeatStreak.SeenToEnd(0),
            MAXIMUM_SEATS_EVER_OBSERVED);
    }

    private static StopDemandStatistics statisticsOf(
        final long routeVersionId
    ) {
        return new StopDemandStatistics(
            routeVersionId,
            TimeSlot.MORNING,
            1,
            List.of(new StopDemandCell(TARGET_STOP_49, 0.5, 0.1, 30, 10)));
    }

    private static RouteStops stops() {
        return new RouteStops(
            ROUTE_VERSION_3330,
            List.of(
                new RouteStop(ROUTE_VERSION_3330, TARGET_STOP_49, "20400049", true),
                new RouteStop(ROUTE_VERSION_3330, LAST_STOP_60, "20400060", true)));
    }
}
