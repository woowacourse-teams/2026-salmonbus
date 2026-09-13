package com.gustler.backend.processor;

import com.gustler.backend.processor.seatdistribution.SameDayFullOutcomes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 계수를 손으로 넣으면 만석 확률이 나오는지 본다.
 *
 * <p>계수 번들이 없어도 계수를 받는 자리는 서야 한다. 여기서 손으로 넣는 자리에 나중에
 * 밖에서 배운 계수가 들어온다. 값이 맞는지는 그때 golden vector 가 본다.
 */
class FullChanceCoefficientsTest {
    /** 오늘 확정된 결과가 없는 자리. 그때는 만석 확률을 안 옮긴다. */
    private static final SameDayFullOutcomes NO_SAME_DAY_OUTCOMES = null;


    private static final Clock KOREAN_CLOCK =
        Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private static final long ROUTE_VERSION_3330 = 1L;

    private static final String UPSTREAM_ROUTE_3330 = "204000057";
    private static final long ANY_OBSERVATION_ID = 7L;
    private static final Instant MORNING_AT = Instant.parse("2026-08-19T08:30:00+09:00");

    private static final int PASSED_STOP_44 = 44;
    private static final int TARGET_STOP_49 = 49;
    private static final int LAST_STOP_60 = 60;
    private static final int SEATS_LEFT = 12;
    private static final int MAXIMUM_SEATS_44 = 44;
    private static final int CROWD_LEVEL_3 = 3;

    private static final int CONSTANT_COLUMN = 1;
    /** 실수 계산에서 자리끝이 어긋나는 만큼. 값이 다른 것과 구별하려고 좁게 잡는다. */
    private static final double FLOATING_POINT_ROUNDING = 1e-12;

    @Test
    void 계수는_설계행렬_열_수와_같은_31개다() {
        // when
        FullChanceCoefficients actual = new FullChanceCoefficients(allZero());

        // then
        assertThat(actual.byColumn()).hasSize(SeatForecastDesignMatrix.COLUMN_COUNT);
    }

    @Test
    void 계수가_31개가_아니면_묶음을_만들_수_없다() {
        // given
        List<Double> tooFew = allZero().subList(0, 30);

        // when, then
        assertThatThrownBy(() -> new FullChanceCoefficients(tooFew))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 계수가_전부_0이면_만석_확률은_0점5다() {
        // when
        final double actual = new FullChanceCoefficients(allZero()).fullChanceBeforeCorrectionOf(matrix());

        // then
        assertThat(actual).isCloseTo(0.5, within(FLOATING_POINT_ROUNDING));
    }

    @Test
    void 상수_열에만_계수가_붙으면_만석_확률은_그_계수의_로지스틱_값이다() {
        // given 상수 열은 늘 1이라 가중합이 그 계수와 같다
        FullChanceCoefficients coefficients = withCoefficientAt(CONSTANT_COLUMN, 2.0);

        // when
        final double actual = coefficients.fullChanceBeforeCorrectionOf(matrix());

        // then
        assertThat(actual).isCloseTo(1 / (1 + Math.exp(-2.0)), within(FLOATING_POINT_ROUNDING));
    }

    @Test
    void 같은_설계행렬과_같은_계수로_두_번_계산하면_같은_만석_확률이_나온다() {
        // given
        FullChanceCoefficients coefficients = withCoefficientAt(CONSTANT_COLUMN, 0.7);

        // when
        final double actual = coefficients.fullChanceBeforeCorrectionOf(matrix());

        // then
        assertThat(actual).isEqualTo(coefficients.fullChanceBeforeCorrectionOf(matrix()));
    }

    private static FullChanceCoefficients withCoefficientAt(
        final int columnNumber,
        final double coefficient
    ) {
        List<Double> byColumn = new ArrayList<>(allZero());
        byColumn.set(columnNumber - 1, coefficient);
        return new FullChanceCoefficients(byColumn);
    }

    private static List<Double> allZero() {
        return Collections.nCopies(SeatForecastDesignMatrix.COLUMN_COUNT, 0.0);
    }

    private static SeatForecastDesignMatrix matrix() {
        return SeatForecastDesignMatrix.of(input());
    }

    private static SeatForecastInput input() {
        ObservedVehicle observation = new ObservedVehicle(
            "204000206", ROUTE_VERSION_3330, PASSED_STOP_44, MORNING_AT, SEATS_LEFT, CROWD_LEVEL_3);
        VehicleTrajectory trajectory = new VehicleTrajectory(
            ANY_OBSERVATION_ID,
            observation,
            new ObservedSeats.Known(SEATS_LEFT),
            new SeatSlope.Known(-3),
            new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD),
            new FullSeatStreak.SeenToEnd(0),
            MAXIMUM_SEATS_44);
        VehicleStopTarget target = new VehicleStopTarget(
            observation, new RouteStop(ROUTE_VERSION_3330, TARGET_STOP_49, "20400049", true));
        return new SeatForecastInput(target, trajectory, statistics(), stops(), TimeSlot.MORNING, NO_SAME_DAY_OUTCOMES);
    }

    private static StopDemandStatistics statistics() {
        return new StopDemandStatistics(
            ROUTE_VERSION_3330, TimeSlot.MORNING, 1, List.of(new StopDemandCell(TARGET_STOP_49, 0.5, 0.1, 30, 10)));
    }

    private static RouteStops stops() {
        List<RouteStop> stops = new ArrayList<>();
        for (int stopOrder = 1; stopOrder <= LAST_STOP_60; stopOrder++) {
            stops.add(new RouteStop(ROUTE_VERSION_3330, stopOrder, "204000%02d".formatted(stopOrder), true));
        }
        return new RouteStops(ROUTE_VERSION_3330, UPSTREAM_ROUTE_3330, stops);
    }
}
