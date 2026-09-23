package com.gustler.backend.forecasting.domain.model;

import com.gustler.backend.forecasting.domain.model.FullSeatStreak;
import com.gustler.backend.forecasting.domain.model.ObservedSeats;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.PrecedingVehicle;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatForecastInput;
import com.gustler.backend.forecasting.domain.model.SeatSlope;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCell;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import com.gustler.backend.forecasting.domain.model.TrajectoryGap;
import com.gustler.backend.forecasting.domain.model.VehicleStopTarget;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import java.time.Instant;
import java.util.List;

/** 예보 재료 한 벌. 값 자체는 아무 수나 좋고, 재료가 계수 계산까지 흘러가는지만 본다. */
final class ForecastInputFixture {

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final long OBSERVATION_ID = 10L;
    private static final int PASSED_STOP_44 = 44;
    private static final int TARGET_STOP_49 = 49;
    private static final int SEATS_LEFT = 20;
    private static final int MAXIMUM_SEATS_EVER_OBSERVED = 44;
    private static final int CROWD_LEVEL_3 = 3;
    private static final Instant MORNING_AT = Instant.parse("2026-08-19T08:30:00+09:00");

    private ForecastInputFixture() {
    }

    static SeatForecastInput of(
        String sourceRouteId
    ) {
        return of(sourceRouteId, SEATS_LEFT, MAXIMUM_SEATS_EVER_OBSERVED);
    }

    static SeatForecastInput of(String sourceRouteId, int remainingSeats, int maximumSeats) {
        ObservedVehicle observation = new ObservedVehicle(
            "204000206", ROUTE_VERSION_3330, PASSED_STOP_44, MORNING_AT, remainingSeats, CROWD_LEVEL_3);
        RouteStop target = new RouteStop(ROUTE_VERSION_3330, TARGET_STOP_49, "20400049", true);
        return new SeatForecastInput(
            new VehicleStopTarget(observation, target),
            new VehicleTrajectory(
                OBSERVATION_ID,
                observation,
                new ObservedSeats.Known(remainingSeats),
                new SeatSlope.Known(-3),
                new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD),
                new FullSeatStreak.SeenToEnd(0),
                maximumSeats),
            new StopDemandStatistics(
                ROUTE_VERSION_3330,
                TimeSlot.MORNING,
                1,
                List.of(new StopDemandCell(TARGET_STOP_49, 0.5, 0.1, 30, 10))),
            new RouteStops(ROUTE_VERSION_3330, sourceRouteId, List.of(target)),
            TimeSlot.MORNING,
            null);
    }
}
