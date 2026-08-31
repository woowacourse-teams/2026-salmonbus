package com.gustler.backend.processor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 지금 계약으로 A18 설계행렬 31열을 실제로 채워 보는 탐침. 프로덕션에 안 들어간다.
 *
 * <p>계수 번들이 없어 구현체가 하나도 없는데, 구현이 없는 계약은 유연한 것이 아니라 검증이 0회인 것이다.
 * 번들이 온 뒤에 계약이 좁은 것을 알면 그때는 예보 경로를 다시 짜야 한다. 그래서 늘 같은 분포를 내는
 * 가짜 구현을 끼워 두고, 31열을 채우려 할 때 어디서 막히는지를 컴파일과 값으로 확인한다.
 *
 * <p>열 정의는 A18 문서 0단계다. 기본 20열 · 정류장 위치 8열 · 정류장 통계 3열.
 * 채울 수 있는 열에는 값이 들어가고 못 채우는 열은 {@link #UNREACHABLE} 로 남는다.
 */
final class A18DesignMatrixProbe implements SeatForecastModel {

    /** 이 열은 지금 계약으로 값을 만들 수 없다. */
    static final double UNREACHABLE = Double.NaN;

    static final int COLUMN_COUNT = 31;

    private static final double LOW_SEAT_BAND = 20;
    private static final double LARGEST_STOP_ORDER = 60;

    /** 늘 같은 값을 낸다. 이 탐침이 재는 것은 정확도가 아니라 계약의 폭이다. */
    private static final double FIXED_FULL_CHANCE = 0.4;

    private final Clock clock;

    A18DesignMatrixProbe(
        Clock clock
    ) {
        this.clock = clock;
    }

    @Override
    public SeatForecastResult predict(
        VehicleStopTarget target
    ) {
        designMatrixOf(target);
        return new SeatForecastResult(
            new SeatDistribution(List.of(FIXED_FULL_CHANCE, 1 - FIXED_FULL_CHANCE)), FIXED_FULL_CHANCE);
    }

    /**
     * 열 번호는 문서와 같은 1부터다. 배열 자리는 하나 작다.
     *
     * <p>못 채우는 열에는 <b>왜</b> 못 채우는지를 주석으로 남긴다. 그 사유가 이 탐침의 산출물이다.
     */
    double[] designMatrixOf(
        VehicleStopTarget target
    ) {
        double[] columns = new double[COLUMN_COUNT];
        Arrays.fill(columns, UNREACHABLE);
        TimeSlot timeSlot = TimeSlot.of(target.observation().observedAt(), clock);
        final int seatsLeft = target.remainingSeats();

        columns[0] = 1;                                                     // 1 상수
        columns[1] = timeSlot == TimeSlot.MORNING ? 1 : 0;                  // 2 아침
        columns[2] = timeSlot == TimeSlot.EVENING ? 1 : 0;                  // 3 저녁
        columns[3] = 0;                                                     // 4 처음 보는 시간대. 번들이 목록을 든다
        // 5 지금 잔여석 비율 v/cap. 정원을 못 얻는다. 그 차량이 보여 준 최대 잔여석인데 관측 한 건만 온다
        columns[5] = seatsLeft == 0 ? 1 : 0;                                // 6 지금 만석인가
        columns[6] = Math.min(seatsLeft, LOW_SEAT_BAND) / LOW_SEAT_BAND;    // 7 낮은 좌석 구간
        // 8~11 혼잡도 네 열. ObservedVehicle 이 crowd_level 을 안 든다
        // 12 정원 cap/68. 5번과 같은 이유
        // 13 상류 좌석 기울기 · 14 기울기 결측. VehicleTrajectory.seatSlope 에 있는데 통로가 없다
        // 15 연속 만석 횟수. VehicleTrajectory.fullSeatStreak 에 있는데 통로가 없다
        // 16~18 직전 차량 셋. VehicleTrajectory.precedingVehicle 에 있는데 통로가 없다
        // 19 노선. 판본 id 는 오는데 노선 표시명이 안 온다
        columns[19] = target.stopOrder() / LARGEST_STOP_ORDER;              // 20 정류장 순번
        // 21~28 위치 기저 여덟 열. u = station / MAXST 인데 그 판본의 정류장 수를 못 얻는다
        // 29~31 셀 통계 셋. StopDemandStatistics 에 있는데 통로가 없다
        return columns;
    }

    static List<Integer> unreachableColumnNumbers(
        double[] columns
    ) {
        List<Integer> unreachable = new ArrayList<>();
        for (int index = 0; index < columns.length; index++) {
            if (Double.isNaN(columns[index])) {
                unreachable.add(index + 1);
            }
        }
        return List.copyOf(unreachable);
    }
}
