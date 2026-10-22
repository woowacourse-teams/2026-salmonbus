package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.forecasting.domain.model.ForecastRouteReference.Stop;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCell;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ForecastFeatureContractTest {
    @Test
    void 기존_계약은_통계까지_기존_31열을_그대로_계산한다() {
        // when
        SeatForecastInput input = ForecastInputFixture.of("204000057");

        // then
        assertThat(ForecastFeatureContract.LEGACY.vectorOf(input))
            .containsExactly(SeatForecastDesignMatrix.of(input).toArray());
    }

    @ParameterizedTest
    @EnumSource(TimeSlot.class)
    void 위치와_방향을_시간대별_열에_같은_순서로_전달한다(TimeSlot slot) {
        // when
        double[] actual = ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(input(slot, RouteDirection.DOWN));

        // then
        assertThat(actual).hasSize(50);
        assertThat(actual[19]).isEqualTo(0.5);
        assertThat(actual[23]).isEqualTo(0.5, within(1e-14));
        assertThat(actual[24]).isEqualTo(0.5, within(1e-14));
        assertThat(actual[31]).isEqualTo(1);
        assertThat(actual[35]).isEqualTo(peakShare(slot, TimeSlot.MORNING), within(1e-14));
        assertThat(actual[40]).isEqualTo(peakIndicator(slot, TimeSlot.MORNING));
        assertThat(actual[44]).isEqualTo(peakShare(slot, TimeSlot.EVENING), within(1e-14));
        assertThat(actual[49]).isEqualTo(peakIndicator(slot, TimeSlot.EVENING));
        assertThat(new double[] {actual[3], actual[28], actual[29], actual[30]}).containsOnly(0);
        assertThat(ForecastFeatureContract.STOP_DIRECTION_TIME.featureNames()).hasSize(50).doesNotHaveDuplicates();
    }

    @Test
    void 같은_정류장_ID라도_방향을_구분한다() {
        // when
        double[] up = ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(input(TimeSlot.MORNING, RouteDirection.UP));
        double[] down =
            ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(input(TimeSlot.MORNING, RouteDirection.DOWN));

        // then
        assertThat(up[31]).isZero();
        assertThat(down[31]).isEqualTo(1);
        assertThat(up[40]).isZero();
        assertThat(down[40]).isEqualTo(1);
    }

    @Test
    void 새_계약은_방향을_모르면_임의로_추정하지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(
            input(TimeSlot.MORNING, RouteDirection.UNKNOWN)));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 검증한_판본도_정류장_내용이_바뀌면_다시_거절한다() {
        // when
        SeatForecastInput input = input(TimeSlot.MORNING, RouteDirection.DOWN);
        ForecastRouteReference reference = new ForecastRouteReference(Map.of("3330", input.stops()
                .stops()
                .stream()
                .map(stop -> new Stop(stop.stopOrder(), stop.stopId(), stop.boardingAllowed(), stop.direction()))
                .toList()));
        reference.requireMatches(input.stops());
        reference.requireMatches(input.stops());
        RouteStop changed = new RouteStop(1L, 49, "different-stop", true, RouteDirection.DOWN);

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> reference.requireMatches(new RouteStops(1L, "204000057",
            List.of(changed, input.stops().stops().get(1)))));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure2 = catchThrowable(
            () -> reference.requireMatches(input(TimeSlot.MORNING, RouteDirection.UP).stops()));

        // then
        assertThat(phaseFailure2)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 통계_사용_계약은_통계_세_열을_유지하고_기존_조건_계약은_0으로_둔다() {
        // when
        SeatForecastInput seed = input(TimeSlot.MORNING, RouteDirection.DOWN);
        StopDemandStatistics statistics = new StopDemandStatistics(1L, TimeSlot.MORNING, 2, List.of(
            new StopDemandCell(49, .2, .1, 10, 2),
            new StopDemandCell(50, .8, -.1, 10, 2)));
        SeatForecastInput input = new SeatForecastInput(seed.target(), seed.trajectory(), statistics,
            seed.stops(), seed.timeSlot(), null);
        double[] actual = ForecastFeatureContract.STOP_DIRECTION_TIME_STATISTICS.vectorOf(input);
        double[] original = SeatForecastDesignMatrix.of(input).toArray();

        // then
        assertThat(actual[28]).isEqualTo(original[28]).isNegative();
        assertThat(actual[29]).isEqualTo(original[29]);
        assertThat(actual[30]).isZero();
        double[] previous = ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(input);
        assertThat(new double[] {previous[28], previous[29], previous[30]}).containsOnly(0);
        for (int index = 0; index < 50; index++) {
            if (index < 28 || index > 30) {
                assertThat(actual[index]).isEqualTo(previous[index]);
            }
        }
    }

    @Test
    void 통계가_없으면_평균값과_결측_표시를_서로_구분한다() {
        // when
        SeatForecastInput seed = input(TimeSlot.MORNING, RouteDirection.DOWN);
        SeatForecastInput input = new SeatForecastInput(seed.target(), seed.trajectory(),
            new StopDemandStatistics(1L, TimeSlot.MORNING, 0, List.of()), seed.stops(), seed.timeSlot(), null);
        double[] actual = ForecastFeatureContract.STOP_DIRECTION_TIME_STATISTICS.vectorOf(input);

        // then
        assertThat(actual[28]).isZero();
        assertThat(actual[29]).isZero();
        assertThat(actual[30]).isEqualTo(1);
    }

    private static SeatForecastInput input(TimeSlot slot, RouteDirection direction) {
        SeatForecastInput seed = ForecastInputFixture.of("204000057");
        RouteStop target = new RouteStop(1L, 49, "same-stop-id", true, direction);
        RouteStops stops = new RouteStops(1L, "204000057", List.of(target,
            new RouteStop(1L, 98, "last-stop", true, RouteDirection.DOWN)));
        return new SeatForecastInput(new VehicleStopTarget(seed.observation(), target), seed.trajectory(),
            new StopDemandStatistics(1L, slot, 1, seed.statistics().cells()), stops, slot, null);
    }

    private static double peakShare(TimeSlot slot, TimeSlot peak) {
        if (slot == peak) {
            return 0.5;
        }
        return 0;
    }

    private static int peakIndicator(TimeSlot slot, TimeSlot peak) {
        if (slot == peak) {
            return 1;
        }
        return 0;
    }
}
