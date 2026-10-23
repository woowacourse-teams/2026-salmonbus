package com.gustler.backend.forecasting.domain.model;

import com.gustler.backend.forecasting.domain.model.SeatSlope.Known;
import com.gustler.backend.forecasting.domain.statistics.TargetStopDemand;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.util.List;
import java.util.stream.Stream;

/**
 * 좌석 예보 하나의 입력을 31개 수로 변환한 설계행렬. 모델이 읽는 첫 입력이다.
 *
 * <p>A18 문서의 0단계다. 열 번호는 문서와 같이 1부터 세고 배열 인덱스는 그보다 하나 작다.
 * 기본 20열 · 정류장 위치 8열 · 정류장 통계 3열로 나뉜다.
 *
 * <p>{@link SeatForecastInput} 하나만 받으며 입력 밖의 값을 사용하지 않는다. 시계도 받지 않으므로
 * 시간대를 여기서 정할 수 없으며 ForecastTimeSlot에서만 결정한다.
 * 과거 시점의 입력을 구성하면 그때의 설계행렬을 같은 경로로 재현한다.
 *
 * <h2>현재 계산하는 열과 계수가 필요한 열</h2>
 *
 * <ul>
 *   <li>21열은 현재 입력으로 계산한다. {@link #COLUMNS_FILLED_NOW}
 *   <li>9열은 학습된 계수가 필요하다. {@link #COLUMNS_WAITING_FOR_COEFFICIENTS}.
 *       {@link #NEW_TIME_SLOT} 은 외부 학습에서 사용한 시간대 목록과 대조해야 계산할 수 있고,
 *       {@link #STOP_POSITION_BASIS_COLUMNS} 여덟 열은 위치 하나를 여덟 열로 변환하는 삼각 기저 함수의
 *       매듭 위치가 공개 문서에 없다. 매듭을 추정해 잘못 넣으면 여덟 열 전체가 어긋난 채
 *       정상처럼 보이는 수를 계산하므로 0으로 둔다
 *   <li>{@link #ROUTE} 는 늘 0이다. 노선마다 따로 학습해 한 적합 안에서 상수이며 절편에 흡수된다
 * </ul>
 *
 * <h2>계수 수신 후 대조할 상수 넷</h2>
 *
 * <p>아래 상수 넷은 입력 생성 규칙에서 정하지만, 근거 문서를 찾지 못해 현재 값을 사용한다.
 * 외부에서 계산한 값과 어긋나면 이곳에 모아 둔 상수를 수정한다.
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

    /** 늘 1인 상수 열. 절편에 대응한다. */
    private static final int CONSTANT = 1;

    /** 아침(7시~9시)에 본 관측인가. */
    private static final int IS_MORNING = 2;

    /** 저녁(17시~20시)에 본 관측인가. */
    private static final int IS_EVENING = 3;

    /** 외부 학습에서 사용하지 않은 시간대인지 나타낸다. 시간대 목록은 계수와 함께 전달된다. */
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

    /** 낮은 등급부터 차례로 나열한다. 혼잡도 등급과 배열 인덱스가 대응한다. */
    private static final List<Integer> CROWD_LEVEL_COLUMNS =
        List.of(CROWD_LEVEL_1, CROWD_LEVEL_2, CROWD_LEVEL_3, CROWD_LEVEL_4);

    /** 그 차량이 보여 준 최대 잔여석을 {@link #LARGEST_SEAT_COUNT} 로 나눈 값. */
    private static final int MAXIMUM_SEATS_RATIO = 12;

    /** 직전 관측 대비 좌석 변화량. 감소하면 음수다. */
    private static final int SEAT_SLOPE = 13;

    /** 좌석 변화량의 결측 여부. */
    private static final int SEAT_SLOPE_MISSING = 14;

    /** 만석이 연속된 정류장 수. */
    private static final int FULL_SEAT_STREAK = 15;

    /** 같은 정류소를 앞서 지난 차의 만석 여부. */
    private static final int PRECEDING_VEHICLE_IS_FULL = 16;

    /** 앞차의 잔여석을 최대 잔여석으로 나눈 값. */
    private static final int PRECEDING_VEHICLE_SEATS_RATIO = 17;

    /** 앞차 정보의 결측 여부. */
    private static final int PRECEDING_VEHICLE_MISSING = 18;

    /** 어느 노선인가. 노선마다 따로 배워서 한 적합 안에서는 늘 같은 값이다. */
    private static final int ROUTE = 19;

    /** 노선에서 대상 정류장의 상대 위치. */
    private static final int STOP_POSITION_ON_ROUTE = 20;

    /** 위치 하나를 여덟 열로 펴는 삼각 기저. 21번부터 28번까지다. */
    private static final List<Integer> STOP_POSITION_BASIS_COLUMNS = List.of(21, 22, 23, 24, 25, 26, 27, 28);

    /** 대상 정류장이 평소 차 있었던 정도. */
    private static final int FILL_RATE_SCORE = 29;

    /** 대상까지 지나갈 구간의 평소 승차량. */
    private static final int NET_BOARDING_SEGMENT_SCORE = 30;

    /** 대상 정류장의 통계가 없어 이웃 통계로 보완했는지 나타낸다. */
    private static final int FILLED_BY_NEIGHBOURS = 31;

    /**
     * 열 번호 순서로 나열한 열 이름. 계수 파일에 적힌 열 이름을 이 목록과 대조한다.
     *
     * <p>이름이나 순서가 하나라도 다르면 다른 의미의 입력으로 학습한 계수다. 개수만 검사하면
     * 값은 정상처럼 보이지만 의미가 다른 예보가 생성되므로 이름과 순서를 정확히 대조한다.
     *
     * <p>21번부터 28번까지 여덟 열은 위치 하나를 변환하는 삼각 기저이므로 이름에 위치 번호가 붙는다.
     */
    public static final List<String> COLUMN_NAMES = List.of(
        "constant",
        "is_morning",
        "is_evening",
        "new_time_slot",
        "seats_left_ratio",
        "is_full",
        "low_seat_band",
        "crowd_level_1",
        "crowd_level_2",
        "crowd_level_3",
        "crowd_level_4",
        "maximum_seats_ratio",
        "seat_slope",
        "seat_slope_missing",
        "full_seat_streak",
        "preceding_vehicle_is_full",
        "preceding_vehicle_seats_ratio",
        "preceding_vehicle_missing",
        "route",
        "stop_position_on_route",
        "stop_position_basis_0",
        "stop_position_basis_1",
        "stop_position_basis_2",
        "stop_position_basis_3",
        "stop_position_basis_4",
        "stop_position_basis_5",
        "stop_position_basis_6",
        "stop_position_basis_7",
        "fill_rate_score",
        "net_boarding_segment_score",
        "filled_by_neighbours");

    /** 현재 입력으로 계산하는 열. {@link #CONSTANT} 만 입력과 관계없이 늘 1이다. */
    public static final List<Integer> COLUMNS_FILLED_NOW = List.of(
        CONSTANT, IS_MORNING, IS_EVENING, SEATS_LEFT_RATIO, IS_FULL, LOW_SEAT_BAND,
        CROWD_LEVEL_1, CROWD_LEVEL_2, CROWD_LEVEL_3, CROWD_LEVEL_4,
        MAXIMUM_SEATS_RATIO, SEAT_SLOPE, SEAT_SLOPE_MISSING, FULL_SEAT_STREAK,
        PRECEDING_VEHICLE_IS_FULL, PRECEDING_VEHICLE_SEATS_RATIO, PRECEDING_VEHICLE_MISSING,
        STOP_POSITION_ON_ROUTE, FILL_RATE_SCORE, NET_BOARDING_SEGMENT_SCORE, FILLED_BY_NEIGHBOURS);

    /** 학습된 계수가 와야 값이 정해지는 열. */
    public static final List<Integer> COLUMNS_WAITING_FOR_COEFFICIENTS =
        Stream.concat(Stream.of(NEW_TIME_SLOT), STOP_POSITION_BASIS_COLUMNS.stream())
            .toList();

    /** 어느 입력에도 없어 0으로 두는 열. */
    public static final List<Integer> COLUMNS_ALWAYS_ZERO = List.of(ROUTE);

    /** 잔여석이 적은 구간을 재는 폭. 이만큼 남으면 1이 되고 그보다 적으면 그 비율이다. */
    private static final double LOW_SEAT_BAND_WIDTH = 20;

    /** 최대 잔여석을 나누는 수. 상류가 준 잔여석 중 가장 큰 값이다. */
    private static final double LARGEST_SEAT_COUNT = 68;

    private static final int NO_SEAT_LEFT = 0;

    /** 입력값을 모를 때 결측 열에 기록하는 값. */
    private static final double MISSING = 1;
    private static final double NOT_MISSING = 0;

    private final double[] columns;

    private SeatForecastDesignMatrix(
        double[] columns
    ) {
        this.columns = columns;
    }

    public static SeatForecastDesignMatrix of(
        SeatForecastInput input
    ) {
        double[] columns = new double[COLUMN_COUNT];
        ObservedVehicle observation = input.observation();
        TimeSlot timeSlot = input.timeSlot();
        VehicleTrajectory trajectory = input.trajectory();
        final int seatsLeft = input.targetRemainingSeats();
        final int targetStopOrder = input.targetStopOrder();
        final double maximumSeats = input.maximumSeatsEverObserved();

        putColumn(columns, CONSTANT, 1);
        putColumn(columns, IS_MORNING, oneIf(timeSlot == TimeSlot.MORNING));
        putColumn(columns, IS_EVENING, oneIf(timeSlot == TimeSlot.EVENING));
        putColumn(columns, SEATS_LEFT_RATIO, seatsLeft / maximumSeats);
        putColumn(columns, IS_FULL, oneIf(seatsLeft == NO_SEAT_LEFT));
        putColumn(columns, LOW_SEAT_BAND, Math.min(seatsLeft, LOW_SEAT_BAND_WIDTH) / LOW_SEAT_BAND_WIDTH);
        putCrowdLevels(columns, observation);
        putColumn(columns, MAXIMUM_SEATS_RATIO, maximumSeats / LARGEST_SEAT_COUNT);
        putSeatSlope(columns, trajectory.seatSlope());
        putColumn(columns, FULL_SEAT_STREAK, trajectory.fullSeatStreak().stopCount());
        putPrecedingVehicle(columns, trajectory.precedingVehicle(), maximumSeats);
        putColumn(columns, STOP_POSITION_ON_ROUTE,
            StopPositionOnRoute.of(targetStopOrder, input.stops()));
        putTargetStopDemand(columns,
            TargetStopDemand.of(input.statistics(), targetStopOrder, input.stopsToTarget()));
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

    /** 좌석 변화량과 결측 여부를 기록한다. 변화량을 모르면 0으로 두고 옆 열에 결측 여부를 표시한다. */
    private static void putSeatSlope(
        double[] columns,
        SeatSlope seatSlope
    ) {
        if (seatSlope instanceof Known known) {
            putColumn(columns, SEAT_SLOPE, known.seatChange());
            putColumn(columns, SEAT_SLOPE_MISSING, NOT_MISSING);
            return;
        }
        putColumn(columns, SEAT_SLOPE, 0);
        putColumn(columns, SEAT_SLOPE_MISSING, MISSING);
    }

    /** 앞차의 만석 여부 · 잔여석 · 결측 여부. */
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
        if (holds) {
            return 1;
        }
        return 0;
    }
}
