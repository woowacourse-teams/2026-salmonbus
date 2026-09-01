package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 설계행렬 31열 중 무엇을 지금 채우고 무엇이 계수 번들을 기다리는지 고정한다.
 *
 * <p>이 테스트가 깨지면 둘 중 하나다. 모델이 받는 재료가 달라졌거나, 설계행렬이 바뀌었거나.
 * 어느 쪽이든 몇 열을 채우는지 다시 세야 한다.
 */
class SeatForecastDesignMatrixTest {

    private static final Clock KOREAN_CLOCK =
        Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private static final long ROUTE_VERSION_3330 = 1L;

    private static final String UPSTREAM_ROUTE_3330 = "204000057";
    private static final long ANY_OBSERVATION_ID = 7L;

    private static final Instant MORNING_AT = Instant.parse("2026-08-19T08:30:00+09:00");
    private static final Instant EVENING_AT = Instant.parse("2026-08-19T18:00:00+09:00");
    private static final Instant MIDDAY_AT = Instant.parse("2026-08-19T13:00:00+09:00");

    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";

    private static final int PASSED_STOP_44 = 44;
    private static final int TARGET_STOP_49 = 49;
    private static final int LAST_STOP_60 = 60;
    private static final int STOPS_TO_TARGET = 5;

    private static final int SEATS_LEFT_12 = 12;
    private static final int NO_SEAT_LEFT = 0;
    private static final int MAXIMUM_SEATS_44 = 44;
    private static final int CROWD_LEVEL_3 = 3;
    private static final Integer CROWD_LEVEL_UNKNOWN = null;

    /**
     * 문서와 같은 1부터의 열 번호. 프로덕션과 따로 적는다.
     *
     * <p>프로덕션 상수를 그대로 쓰면 열 번호가 바뀔 때 테스트도 같이 따라가서 아무것도 안 잡는다.
     */
    private static final int CONSTANT = 1;
    private static final int IS_MORNING = 2;
    private static final int IS_EVENING = 3;
    private static final int SEATS_LEFT_RATIO = 5;
    private static final int IS_FULL = 6;
    private static final int LOW_SEAT_BAND = 7;
    private static final int CROWD_LEVEL_1 = 8;
    private static final int MAXIMUM_SEATS_RATIO = 12;
    private static final int SEAT_SLOPE = 13;
    private static final int SEAT_SLOPE_MISSING = 14;
    private static final int FULL_SEAT_STREAK = 15;
    private static final int PRECEDING_VEHICLE_IS_FULL = 16;
    private static final int PRECEDING_VEHICLE_SEATS_RATIO = 17;
    private static final int PRECEDING_VEHICLE_MISSING = 18;
    private static final int ROUTE = 19;
    private static final int STOP_POSITION_ON_ROUTE = 20;
    private static final int FILL_RATE_SCORE = 29;
    private static final int NET_BOARDING_SEGMENT_SCORE = 30;
    private static final int FILLED_BY_NEIGHBOURS = 31;

    /** 실수 계산에서 자리끝이 어긋나는 만큼. 값이 다른 것과 구별하려고 좁게 잡는다. */
    private static final double FLOATING_POINT_ROUNDING = 1e-12;

    @Test
    void 설계행렬은_31열이다() {
        // when
        double[] actual = matrixOf(input()).toArray();

        // then
        assertThat(actual).hasSize(31);
    }

    @Test
    void 지금_채우는_열과_계수를_기다리는_열과_늘_0인_열이_31열을_빠짐없이_나눈다() {
        // when
        List<Integer> actual = new ArrayList<>();
        actual.addAll(SeatForecastDesignMatrix.COLUMNS_FILLED_NOW);
        actual.addAll(SeatForecastDesignMatrix.COLUMNS_WAITING_FOR_COEFFICIENTS);
        actual.addAll(SeatForecastDesignMatrix.COLUMNS_ALWAYS_ZERO);

        // then 겹치는 열도 빠진 열도 없다
        assertThat(actual).containsExactlyInAnyOrderElementsOf(
            IntStream.rangeClosed(1, SeatForecastDesignMatrix.COLUMN_COUNT).boxed().toList());
    }

    @Test
    void 지금_채우는_열은_스물한_개다() {
        // then 계약을 넓히기 전에는 여섯 개였다
        assertThat(SeatForecastDesignMatrix.COLUMNS_FILLED_NOW).hasSize(21);
    }

    @Test
    void 계수를_기다리는_아홉_열은_재료가_달라져도_0이다() {
        // given 시간대도 잔여석도 혼잡도도 다른 두 재료
        SeatForecastDesignMatrix morning = matrixOf(input());
        SeatForecastDesignMatrix evening = matrixOf(
            inputOf(observed(NO_SEAT_LEFT, CROWD_LEVEL_UNKNOWN, EVENING_AT), statisticsOf(TimeSlot.EVENING)));

        // when
        List<Double> actual = SeatForecastDesignMatrix.COLUMNS_WAITING_FOR_COEFFICIENTS.stream()
            .flatMap(column -> List.of(morning.columnAt(column), evening.columnAt(column)).stream())
            .toList();

        // then
        assertThat(actual).containsOnly(0.0);
    }

    @Test
    void 노선은_노선마다_따로_배워서_0이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(input());

        // then 한 적합 안에서 상수라 절편에 흡수된다
        assertThat(actual.columnAt(ROUTE)).isEqualTo(0);
    }

    @Test
    void 상수는_재료와_무관하게_1이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(input());

        // then
        assertThat(actual.columnAt(CONSTANT)).isEqualTo(1);
    }

    @Test
    void 아침으로_정해진_예보는_아침_열이_1이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(input());

        // then
        assertThat(actual.columnAt(IS_MORNING)).isEqualTo(1);
    }

    @Test
    void 저녁으로_정해진_예보는_저녁_열이_1이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(
            inputOf(observed(SEATS_LEFT_12, CROWD_LEVEL_3, EVENING_AT), statisticsOf(TimeSlot.EVENING)));

        // then
        assertThat(actual.columnAt(IS_EVENING)).isEqualTo(1);
    }

    @Test
    void 아침도_저녁도_아닌_예보는_두_열이_모두_0이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(
            inputOf(observed(SEATS_LEFT_12, CROWD_LEVEL_3, MIDDAY_AT), statisticsOf(TimeSlot.OTHER)));

        // then
        assertThat(actual.columnAt(IS_MORNING) + actual.columnAt(IS_EVENING)).isEqualTo(0);
    }

    @Test
    void 최대_44석을_보인_차량에_12석_남았으면_잔여석_비율은_12를_44로_나눈_값이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(input());

        // then
        assertThat(actual.columnAt(SEATS_LEFT_RATIO)).isCloseTo(12.0 / 44, within(FLOATING_POINT_ROUNDING));
    }

    @Test
    void 잔여석이_0석이면_만석이_1이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(
            inputOf(observed(NO_SEAT_LEFT, CROWD_LEVEL_3, MORNING_AT), fullStatistics()));

        // then
        assertThat(actual.columnAt(IS_FULL)).isEqualTo(1);
    }

    @Test
    void 잔여석이_20석보다_많이_남으면_낮은_좌석_구간은_1이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(
            inputOf(observed(30, CROWD_LEVEL_3, MORNING_AT), fullStatistics()));

        // then
        assertThat(actual.columnAt(LOW_SEAT_BAND)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void 혼잡도_1등급부터_4등급까지_그_등급의_열만_1이다(
        final int crowdLevel
    ) {
        // when
        SeatForecastDesignMatrix actual = matrixOf(
            inputOf(observed(SEATS_LEFT_12, crowdLevel, MORNING_AT), fullStatistics()));

        // then
        assertThat(crowdLevelColumnsOf(actual))
            .containsExactlyElementsOf(onlyOneIsSetAt(crowdLevel));
    }

    @Test
    void 혼잡도를_모르면_혼잡도_네_열이_모두_0이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(
            inputOf(observed(SEATS_LEFT_12, CROWD_LEVEL_UNKNOWN, MORNING_AT), fullStatistics()));

        // then
        assertThat(crowdLevelColumnsOf(actual)).containsOnly(0.0);
    }

    @Test
    void 최대_44석을_보인_차량은_최대_잔여석_비율이_44를_68로_나눈_값이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(input());

        // then
        assertThat(actual.columnAt(MAXIMUM_SEATS_RATIO)).isCloseTo(44.0 / 68, within(FLOATING_POINT_ROUNDING));
    }

    @Test
    void 상류에서_3석_줄었으면_좌석_기울기는_마이너스_3이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(input());

        // then
        assertThat(actual.columnAt(SEAT_SLOPE)).isEqualTo(-3);
    }

    @Test
    void 상류_좌석_기울기를_모르면_기울기_결측이_1이다() {
        // given
        VehicleTrajectory unknownSlope = trajectoryOf(
            observed(SEATS_LEFT_12, CROWD_LEVEL_3, MORNING_AT),
            new SeatSlope.Unknown(TrajectoryGap.NO_EARLIER_OBSERVATION),
            precedingVehicle(),
            new FullSeatStreak.SeenToEnd(3));

        // when
        SeatForecastDesignMatrix actual = matrixOf(inputOf(unknownSlope, fullStatistics()));

        // then
        assertThat(actual.columnAt(SEAT_SLOPE_MISSING)).isEqualTo(1);
    }

    @Test
    void 만석이_3정류장째_이어지면_연속_만석은_3이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(input());

        // then
        assertThat(actual.columnAt(FULL_SEAT_STREAK)).isEqualTo(3);
    }

    @Test
    void 직전_차량이_만석이었으면_직전_만석은_1이다() {
        // given
        VehicleTrajectory fullPreceding = trajectoryOf(
            observed(SEATS_LEFT_12, CROWD_LEVEL_3, MORNING_AT),
            new SeatSlope.Known(-3),
            new PrecedingVehicle.Known(VEHICLE_204003542, NO_SEAT_LEFT, MORNING_AT),
            new FullSeatStreak.SeenToEnd(3));

        // when
        SeatForecastDesignMatrix actual = matrixOf(inputOf(fullPreceding, fullStatistics()));

        // then
        assertThat(actual.columnAt(PRECEDING_VEHICLE_IS_FULL)).isEqualTo(1);
    }

    @Test
    void 직전_차량에_11석_남았으면_직전_잔여석_비율은_11을_44로_나눈_값이다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(input());

        // then
        assertThat(actual.columnAt(PRECEDING_VEHICLE_SEATS_RATIO)).isCloseTo(11.0 / 44, within(FLOATING_POINT_ROUNDING));
    }

    @Test
    void 직전_차량을_모르면_직전_결측이_1이다() {
        // given
        VehicleTrajectory noPreceding = trajectoryOf(
            observed(SEATS_LEFT_12, CROWD_LEVEL_3, MORNING_AT),
            new SeatSlope.Known(-3),
            new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD),
            new FullSeatStreak.SeenToEnd(3));

        // when
        SeatForecastDesignMatrix actual = matrixOf(inputOf(noPreceding, fullStatistics()));

        // then
        assertThat(actual.columnAt(PRECEDING_VEHICLE_MISSING)).isEqualTo(1);
    }

    @Test
    void 정류장_60개짜리_판본의_49번_정류장은_49를_60으로_나눈_자리다() {
        // when
        SeatForecastDesignMatrix actual = matrixOf(input());

        // then
        assertThat(actual.columnAt(STOP_POSITION_ON_ROUTE)).isCloseTo(49.0 / 60, within(FLOATING_POINT_ROUNDING));
    }

    @Test
    void 대상_정류장의_셀이_없으면_이웃으로_메웠다고_남는다() {
        // given 49번 셀이 없는 세대
        StopDemandStatistics withoutTargetCell = new StopDemandStatistics(
            ROUTE_VERSION_3330, TimeSlot.MORNING, 1, List.of(new StopDemandCell(47, 0.5, 0.1, 30, 10)));

        // when
        SeatForecastDesignMatrix actual = matrixOf(
            inputOf(observed(SEATS_LEFT_12, CROWD_LEVEL_3, MORNING_AT), withoutTargetCell));

        // then
        assertThat(actual.columnAt(FILLED_BY_NEIGHBOURS)).isEqualTo(1);
    }

    @Test
    void 셀_통계_두_열은_대상_정류장과_지나갈_구간에서_나온다() {
        // given 대상 49번만 크게 차 있고 지나갈 구간의 순승차도 크다
        StopDemandStatistics statistics = new StopDemandStatistics(
            ROUTE_VERSION_3330,
            TimeSlot.MORNING,
            1,
            List.of(
                new StopDemandCell(40, 0.1, -0.2, 30, 10),
                new StopDemandCell(TARGET_STOP_49, 0.9, 0.3, 30, 10)));

        // when
        SeatForecastDesignMatrix actual = matrixOf(
            inputOf(observed(SEATS_LEFT_12, CROWD_LEVEL_3, MORNING_AT), statistics));

        // then
        assertThat(actual.columnAt(FILL_RATE_SCORE) + actual.columnAt(NET_BOARDING_SEGMENT_SCORE)).isPositive();
    }

    @Test
    void 같은_재료로_두_번_만들면_31열이_모두_같다() {
        // when
        double[] actual = matrixOf(input()).toArray();

        // then
        assertThat(actual).isEqualTo(matrixOf(input()).toArray());
    }

    private static List<Double> crowdLevelColumnsOf(
        SeatForecastDesignMatrix matrix
    ) {
        return IntStream.range(0, 4)
            .mapToObj(offset -> matrix.columnAt(CROWD_LEVEL_1 + offset))
            .toList();
    }

    private static List<Double> onlyOneIsSetAt(
        final int crowdLevel
    ) {
        return IntStream.rangeClosed(1, 4)
            .mapToObj(level -> level == crowdLevel ? 1.0 : 0.0)
            .toList();
    }

    private static SeatForecastDesignMatrix matrixOf(
        SeatForecastInput input
    ) {
        return SeatForecastDesignMatrix.of(input);
    }

    private static SeatForecastInput input() {
        return inputOf(observed(SEATS_LEFT_12, CROWD_LEVEL_3, MORNING_AT), fullStatistics());
    }

    private static SeatForecastInput inputOf(
        ObservedVehicle observation,
        StopDemandStatistics statistics
    ) {
        return inputOf(observation, statistics, statistics.timeSlot());
    }

    private static SeatForecastInput inputOf(
        ObservedVehicle observation,
        StopDemandStatistics statistics,
        TimeSlot timeSlot
    ) {
        return inputOf(
            trajectoryOf(observation, new SeatSlope.Known(-3), precedingVehicle(), new FullSeatStreak.SeenToEnd(3)),
            statistics,
            timeSlot);
    }

    private static SeatForecastInput inputOf(
        VehicleTrajectory trajectory,
        StopDemandStatistics statistics
    ) {
        return inputOf(trajectory, statistics, statistics.timeSlot());
    }

    private static SeatForecastInput inputOf(
        VehicleTrajectory trajectory,
        StopDemandStatistics statistics,
        TimeSlot timeSlot
    ) {
        VehicleStopTarget target = new VehicleStopTarget(
            trajectory.observation(), new RouteStop(ROUTE_VERSION_3330, TARGET_STOP_49, "20400049", true));
        return new SeatForecastInput(target, trajectory, statistics, stops(), timeSlot);
    }

    private static ObservedVehicle observed(
        final int seatsLeft,
        Integer crowdLevel,
        Instant observedAt
    ) {
        return new ObservedVehicle(
            VEHICLE_204000206, ROUTE_VERSION_3330, PASSED_STOP_44, observedAt, seatsLeft, crowdLevel);
    }

    private static VehicleTrajectory trajectoryOf(
        ObservedVehicle observation,
        SeatSlope seatSlope,
        PrecedingVehicle precedingVehicle,
        FullSeatStreak fullSeatStreak
    ) {
        return new VehicleTrajectory(
            ANY_OBSERVATION_ID,
            observation,
            new ObservedSeats.Known(observation.remainingSeats()),
            seatSlope,
            precedingVehicle,
            fullSeatStreak,
            MAXIMUM_SEATS_44);
    }

    private static PrecedingVehicle precedingVehicle() {
        return new PrecedingVehicle.Known(VEHICLE_204003542, 11, MORNING_AT);
    }

    /** 대상 49번 셀이 있는 세대. 지나갈 구간 다섯 정류장에도 셀이 있다. */
    private static StopDemandStatistics fullStatistics() {
        return statisticsOf(TimeSlot.MORNING);
    }

    private static StopDemandStatistics statisticsOf(
        TimeSlot timeSlot
    ) {
        List<StopDemandCell> cells = new ArrayList<>();
        for (int stopOrder = TARGET_STOP_49 - STOPS_TO_TARGET + 1; stopOrder <= TARGET_STOP_49; stopOrder++) {
            cells.add(new StopDemandCell(stopOrder, 0.5, 0.1, 30, 10));
        }
        return new StopDemandStatistics(ROUTE_VERSION_3330, timeSlot, 1, cells);
    }

    private static RouteStops stops() {
        List<RouteStop> stops = new ArrayList<>();
        for (int stopOrder = 1; stopOrder <= LAST_STOP_60; stopOrder++) {
            stops.add(new RouteStop(ROUTE_VERSION_3330, stopOrder, "204000%02d".formatted(stopOrder), true));
        }
        return new RouteStops(ROUTE_VERSION_3330, UPSTREAM_ROUTE_3330, stops);
    }
}
