package com.gustler.backend.processor;

import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

/**
 * 좌석 예보 하나를 계산하려고 재료를 수 31개로 편 것. 모델이 읽는 첫 입력이다.
 *
 * <p>문서가 설계행렬이라고 부르는 것이고 A18 문서의 0단계다. 열 번호는 문서와 같이 1부터 세고
 * 배열 자리는 하나 작다. 기본 20열 · 정류장 위치 8열 · 정류장 통계 3열로 나뉜다.
 *
 * <p><b>재료 밖에서 오는 값이 없다.</b> {@link SeatForecastInput} 하나와 시간대를 가르는 시계만
 * 받는다. 그래서 과거 시점 재료를 손으로 만들어 넣으면 그때의 설계행렬이 그대로 다시 나온다.
 *
 * <h2>지금 채우는 열과 못 채우는 열</h2>
 *
 * <ul>
 *   <li><b>21열을 지금 채운다.</b> {@link #COLUMNS_FILLED_NOW}
 *   <li><b>9열은 학습된 계수를 기다린다.</b> {@link #COLUMNS_WAITING_FOR_COEFFICIENTS}.
 *       {@link #NEW_TIME_SLOT} 은 밖에서 배울 때 쓴 시간대 목록과 대조해야 나오고,
 *       {@link #STOP_POSITION_BASIS_COLUMNS} 여덟 열은 위치 하나를 여덟 열로 펴는 삼각 기저 함수의
 *       매듭 위치가 공개 문서에 없다. 매듭을 짐작해 넣으면 틀렸을 때 여덟 열이 통째로 어긋난 채
 *       그럴듯한 수를 낸다. 그래서 0으로 둔다
 *   <li><b>{@link #ROUTE} 는 늘 0이다.</b> 노선마다 따로 배워서 한 적합 안에서 상수다. 절편에 흡수된다
 * </ul>
 *
 * <h2>계수가 오면 맞춰 볼 상수 넷</h2>
 *
 * <p>아래 넷은 재료를 만드는 규칙이 소유하는데 근거 문서를 못 찾아 지금 값으로 둔 것이다.
 * 여기 모아 두었으니 밖에서 낸 값과 어긋나면 여기만 고치면 된다.
 *
 * <ul>
 *   <li>{@link #LOW_SEAT_BAND_WIDTH} 잔여석이 적은 구간을 재는 폭
 *   <li>{@link #LARGEST_SEAT_COUNT} {@link #MAXIMUM_SEATS_RATIO} 에서 나누는 수
 *   <li>{@link #PRECEDING_VEHICLE_SEATS_RATIO} 를 {@link #SEATS_LEFT_RATIO} 처럼 비율로 둘지
 *       좌석 수 그대로 둘지. 지금은 비율이다
 *   <li>{@link #STOP_POSITION_ON_ROUTE} 를 0에서 1로 정규화할지 순번 그대로 둘지. 지금은 정규화한다
 * </ul>
 */
public final class SeatForecastDesignMatrix {

    public static final int COLUMN_COUNT = 31;

    /** 늘 1. 절편이 붙는 자리다. */
    private static final int CONSTANT = 1;

    /** 아침(7시~9시)에 본 관측인가. */
    private static final int IS_MORNING = 2;

    /** 저녁(17시~20시)에 본 관측인가. */
    private static final int IS_EVENING = 3;

    /** 밖에서 배울 때 안 본 시간대인가. 그 시간대 목록이 계수와 같이 온다. */
    private static final int NEW_TIME_SLOT = 4;

    /** 지금 잔여석을 그 차량이 보여 준 최대 잔여석으로 나눈 값. */
    private static final int SEATS_LEFT_RATIO = 5;

    /** 지금 만석인가. */
    private static final int IS_FULL = 6;

    /** 잔여석이 적은 구간의 어디쯤인가. 폭만큼 남으면 1이고 그보다 적으면 그 비율이다. */
    private static final int LOW_SEAT_BAND = 7;

    /** 혼잡도 등급마다 한 열씩. 그 등급이면 그 열만 1이고 등급을 모르면 넷 다 0이다. */
    private static final int CROWD_LEVEL_1 = 8;
    private static final int CROWD_LEVEL_2 = 9;
    private static final int CROWD_LEVEL_3 = 10;
    private static final int CROWD_LEVEL_4 = 11;

    /** 낮은 등급부터 차례로. 혼잡도 등급과 자리가 맞는다. */
    private static final List<Integer> CROWD_LEVEL_COLUMNS =
        List.of(CROWD_LEVEL_1, CROWD_LEVEL_2, CROWD_LEVEL_3, CROWD_LEVEL_4);

    /** 그 차량이 보여 준 최대 잔여석을 {@link #LARGEST_SEAT_COUNT} 로 나눈 값. */
    private static final int MAXIMUM_SEATS_RATIO = 12;

    /** 직전 관측 대비 좌석이 몇 석 변했나. 줄면 음수다. */
    private static final int SEAT_SLOPE = 13;

    /** 좌석 변화를 모르는가. */
    private static final int SEAT_SLOPE_MISSING = 14;

    /** 만석이 몇 정류장째 이어지고 있나. */
    private static final int FULL_SEAT_STREAK = 15;

    /** 같은 정류소를 앞서 지난 차가 만석이었나. */
    private static final int PRECEDING_VEHICLE_IS_FULL = 16;

    /** 앞차의 잔여석을 최대 잔여석으로 나눈 값. */
    private static final int PRECEDING_VEHICLE_SEATS_RATIO = 17;

    /** 앞차를 모르는가. */
    private static final int PRECEDING_VEHICLE_MISSING = 18;

    /** 어느 노선인가. 노선마다 따로 배워서 한 적합 안에서는 늘 같은 값이다. */
    private static final int ROUTE = 19;

    /** 대상 정류장이 노선의 어디쯤인가. */
    private static final int STOP_POSITION_ON_ROUTE = 20;

    /** 위치 하나를 여덟 열로 펴는 삼각 기저. 21번부터 28번까지다. */
    private static final List<Integer> STOP_POSITION_BASIS_COLUMNS = List.of(21, 22, 23, 24, 25, 26, 27, 28);

    /** 대상 정류장이 평소 얼마나 차 있었나. */
    private static final int FILL_RATE_SCORE = 29;

    /** 대상까지 지나갈 구간에서 평소 얼마나 탔나. */
    private static final int NET_BOARDING_SEGMENT_SCORE = 30;

    /** 대상 정류장의 통계가 없어 이웃으로 메웠나. */
    private static final int FILLED_BY_NEIGHBOURS = 31;

    /** 지금 채우는 열. 재료에서 값이 나온다. {@link #CONSTANT} 만 재료 없이 늘 1이다. */
    public static final List<Integer> COLUMNS_FILLED_NOW = List.of(
        CONSTANT, IS_MORNING, IS_EVENING, SEATS_LEFT_RATIO, IS_FULL, LOW_SEAT_BAND,
        CROWD_LEVEL_1, CROWD_LEVEL_2, CROWD_LEVEL_3, CROWD_LEVEL_4,
        MAXIMUM_SEATS_RATIO, SEAT_SLOPE, SEAT_SLOPE_MISSING, FULL_SEAT_STREAK,
        PRECEDING_VEHICLE_IS_FULL, PRECEDING_VEHICLE_SEATS_RATIO, PRECEDING_VEHICLE_MISSING,
        STOP_POSITION_ON_ROUTE, FILL_RATE_SCORE, NET_BOARDING_SEGMENT_SCORE, FILLED_BY_NEIGHBOURS);

    /** 학습된 계수가 와야 값이 정해지는 열. */
    public static final List<Integer> COLUMNS_WAITING_FOR_COEFFICIENTS =
        Stream.concat(Stream.of(NEW_TIME_SLOT), STOP_POSITION_BASIS_COLUMNS.stream()).toList();

    /** 어느 재료에도 없어 0으로 두는 열. */
    public static final List<Integer> COLUMNS_ALWAYS_ZERO = List.of(ROUTE);

    /** 잔여석이 적은 구간을 재는 폭. 이만큼 남으면 1이 되고 그보다 적으면 그 비율이다. */
    private static final double LOW_SEAT_BAND_WIDTH = 20;

    /** 최대 잔여석을 나누는 수. 상류가 준 잔여석 중 가장 큰 값이다. */
    private static final double LARGEST_SEAT_COUNT = 68;

    private static final int NO_SEAT_LEFT = 0;

    /** 그 재료를 모를 때 결측 열에 서는 값. */
    private static final double MISSING = 1;
    private static final double NOT_MISSING = 0;

    private final double[] columns;

    private SeatForecastDesignMatrix(
        double[] columns
    ) {
        this.columns = columns;
    }

    public static SeatForecastDesignMatrix of(
        SeatForecastInput input,
        Clock clock
    ) {
        double[] columns = new double[COLUMN_COUNT];
        ObservedVehicle observation = input.observation();
        TimeSlot timeSlot = TimeSlot.of(observation.observedAt(), clock);
        final int seatsLeft = input.target().remainingSeats();
        final double maximumSeats = input.maximumSeatsEverObserved();

        putColumn(columns, CONSTANT, 1);
        putColumn(columns, IS_MORNING, oneIf(timeSlot == TimeSlot.MORNING));
        putColumn(columns, IS_EVENING, oneIf(timeSlot == TimeSlot.EVENING));
        putColumn(columns, SEATS_LEFT_RATIO, seatsLeft / maximumSeats);
        putColumn(columns, IS_FULL, oneIf(seatsLeft == NO_SEAT_LEFT));
        putColumn(columns, LOW_SEAT_BAND, Math.min(seatsLeft, LOW_SEAT_BAND_WIDTH) / LOW_SEAT_BAND_WIDTH);
        putCrowdLevels(columns, observation);
        putColumn(columns, MAXIMUM_SEATS_RATIO, maximumSeats / LARGEST_SEAT_COUNT);
        putSeatSlope(columns, input.trajectory().seatSlope());
        putColumn(columns, FULL_SEAT_STREAK, input.trajectory().fullSeatStreak().stopCount());
        putPrecedingVehicle(columns, input.trajectory().precedingVehicle(), maximumSeats);
        putColumn(columns, STOP_POSITION_ON_ROUTE,
            StopPositionOnRoute.of(input.target().stopOrder(), input.stops()));
        putTargetStopDemand(columns, TargetStopDemand.of(input.statistics(), input.target()));
        return new SeatForecastDesignMatrix(columns);
    }

    /** 문서와 같은 1부터의 열 번호로 읽는다. */
    public double columnAt(
        final int columnNumber
    ) {
        return columns[columnNumber - 1];
    }

    public double[] toArray() {
        return columns.clone();
    }

    /** 혼잡도 네 등급을 등급마다 한 열로 편다. 등급을 모르면 넷 다 0이다. */
    private static void putCrowdLevels(
        double[] columns,
        ObservedVehicle observation
    ) {
        for (int index = 0; index < CROWD_LEVEL_COLUMNS.size(); index++) {
            final int level = ObservedVehicle.LOWEST_CROWD_LEVEL + index;
            final boolean isThisLevel =
                observation.hasKnownCrowdLevel() && observation.crowdLevel() == level;
            putColumn(columns, CROWD_LEVEL_COLUMNS.get(index), oneIf(isThisLevel));
        }
    }

    /** 좌석이 몇 석 변했나와 그것을 모르는가. 모르면 변화를 0으로 두고 옆 열이 모른다고 말한다. */
    private static void putSeatSlope(
        double[] columns,
        SeatSlope seatSlope
    ) {
        if (seatSlope instanceof SeatSlope.Known known) {
            putColumn(columns, SEAT_SLOPE, known.seatChange());
            putColumn(columns, SEAT_SLOPE_MISSING, NOT_MISSING);
            return;
        }
        putColumn(columns, SEAT_SLOPE, 0);
        putColumn(columns, SEAT_SLOPE_MISSING, MISSING);
    }

    /** 앞차가 만석이었나 · 얼마나 남았나 · 아예 모르나. */
    private static void putPrecedingVehicle(
        double[] columns,
        PrecedingVehicle precedingVehicle,
        final double maximumSeats
    ) {
        if (precedingVehicle instanceof PrecedingVehicle.Known known) {
            putColumn(columns, PRECEDING_VEHICLE_IS_FULL, oneIf(known.remainingSeats() == NO_SEAT_LEFT));
            putColumn(columns, PRECEDING_VEHICLE_SEATS_RATIO, known.remainingSeats() / maximumSeats);
            putColumn(columns, PRECEDING_VEHICLE_MISSING, NOT_MISSING);
            return;
        }
        putColumn(columns, PRECEDING_VEHICLE_IS_FULL, 0);
        putColumn(columns, PRECEDING_VEHICLE_SEATS_RATIO, 0);
        putColumn(columns, PRECEDING_VEHICLE_MISSING, MISSING);
    }

    private static void putTargetStopDemand(
        double[] columns,
        TargetStopDemand demand
    ) {
        putColumn(columns, FILL_RATE_SCORE, demand.fillRateScore());
        putColumn(columns, NET_BOARDING_SEGMENT_SCORE, demand.netBoardingSegmentScore());
        putColumn(columns, FILLED_BY_NEIGHBOURS, oneIf(demand.filledByNeighbours()));
    }

    private static void putColumn(
        double[] columns,
        final int columnNumber,
        final double value
    ) {
        columns[columnNumber - 1] = value;
    }

    /** 그런 경우면 1, 아니면 0. 설계행렬은 참과 거짓도 수로 받는다. */
    private static double oneIf(
        final boolean holds
    ) {
        return holds ? 1 : 0;
    }
}
