package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.domain.model.FullSeatStreak.SeenToEnd;
import com.gustler.backend.forecasting.domain.model.ObservedSeats.Known;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.PrecedingVehicle.Unknown;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SameDayFullOutcomes;
import com.gustler.backend.forecasting.domain.model.SeatSlope;
import com.gustler.backend.forecasting.domain.model.TrajectoryGap;
import com.gustler.backend.forecasting.domain.model.VehicleStopTarget;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCell;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 모델 입력을 직접 구성할 수 있는지와 서로 맞지 않는 입력을 거절하는지 확인한다.
 *
 * <p>DB 나 배치 없이 과거 시점의 입력을 직접 구성해 예보를 재현하므로 백테스트에도 사용할 수 있다.
 */
class SeatForecastInputTest {
    /** 오늘 확정된 결과가 없어 만석 확률을 보정하지 않는 경우. */
    private static final SameDayFullOutcomes NO_SAME_DAY_OUTCOMES = null;

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final long ROUTE_VERSION_1650 = 2L;

    private static final String UPSTREAM_ROUTE_3330 = "204000057";
    private static final String UPSTREAM_ROUTE_1650 = "234000050";
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
            target(), trajectoryOf(observed(VEHICLE_204000206)), statisticsOf(ROUTE_VERSION_3330), stops(),
            TimeSlot.MORNING, NO_SAME_DAY_OUTCOMES);

        // then
        assertThat(actual.maximumSeatsEverObserved()).isEqualTo(MAXIMUM_SEATS_EVER_OBSERVED);
    }

    @Test
    void 궤적이_다른_차량의_것이면_모델_입력을_만들_수_없다() {
        // when
        VehicleTrajectory otherVehicle = trajectoryOf(observed(VEHICLE_204003542));


        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> new SeatForecastInput(
            target(), otherVehicle, statisticsOf(ROUTE_VERSION_3330), stops(), TimeSlot.MORNING, NO_SAME_DAY_OUTCOMES));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 셀_통계가_다른_노선_판본의_것이면_모델_입력을_만들_수_없다() {
        // when
        StopDemandStatistics otherVersion = statisticsOf(ROUTE_VERSION_1650);


        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> new SeatForecastInput(
            target(), trajectoryOf(observed(VEHICLE_204000206)), otherVersion, stops(), TimeSlot.MORNING,
            NO_SAME_DAY_OUTCOMES));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정류장_목록이_다른_노선_판본의_것이면_모델_입력을_만들_수_없다() {
        // when
        RouteStops otherVersion = new RouteStops(
            ROUTE_VERSION_1650,
            UPSTREAM_ROUTE_1650,
            List.of(new RouteStop(ROUTE_VERSION_1650, TARGET_STOP_49, "20400049", true)));


        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> new SeatForecastInput(
            target(), trajectoryOf(observed(VEHICLE_204000206)), statisticsOf(ROUTE_VERSION_3330), otherVersion,
            TimeSlot.MORNING, NO_SAME_DAY_OUTCOMES));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 셀_통계와_다른_시간대로는_모델_입력을_만들_수_없다() {
        // given
        // 셀 통계는 아침 세대인데 예보는 저녁으로 정해졌다

        StopDemandStatistics morning = statisticsOf(ROUTE_VERSION_3330);

        // when
        Throwable caughtFailure = catchThrowable(() -> new SeatForecastInput(
            target(), trajectoryOf(observed(VEHICLE_204000206)), morning, stops(), TimeSlot.EVENING,
            NO_SAME_DAY_OUTCOMES));

        // then
        assertThat(caughtFailure)
            .isNotNull()
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
            new Known(SEATS_LEFT),
            new SeatSlope.Known(-3),
            new Unknown(TrajectoryGap.NO_VEHICLE_AHEAD),
            new SeenToEnd(0),
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
            UPSTREAM_ROUTE_3330,
            List.of(
                new RouteStop(ROUTE_VERSION_3330, TARGET_STOP_49, "20400049", true),
                new RouteStop(ROUTE_VERSION_3330, LAST_STOP_60, "20400060", true)));
    }
}
