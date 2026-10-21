package com.gustler.backend.forecasting.domain.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 번들과 함께 선택하는 입력 규칙. 기존 31열 계산을 새 모델 때문에 바꾸지 않는다. */
public enum ForecastFeatureContract {
    LEGACY,
    STOP_DIRECTION_TIME,
    STOP_DIRECTION_TIME_STATISTICS;

    public static final String CONDITIONAL_VERSION = "stop-direction-time-v1";
    public static final String STATISTICS_VERSION = "stop-direction-time-stats-v1";
    public static final String STATISTICS_CALCULATION_VERSION = "observed-max-capacity-v1";
    public static final String STATISTICS_POLICY = STATISTICS_CALCULATION_VERSION + ";as-of-observation";
    private static final int BASIS_COUNT = 8;
    private static final int BASIS_START = 20;
    private static final int DOWN_COLUMN = 31;
    private static final List<String> CONDITIONAL_NAMES = conditionalNames();

    public List<String> featureNames() {
        if (this == LEGACY) {
            return SeatForecastDesignMatrix.COLUMN_NAMES;
        }
        return CONDITIONAL_NAMES;
    }

    public double[] vectorOf(SeatForecastInput input) {
        double[] original = SeatForecastDesignMatrix.of(input).toArray();
        if (this == LEGACY) {
            return original;
        }
        RouteStop target = input.targetStop();
        if (target.direction() == RouteDirection.UNKNOWN || !input.stops().stops().contains(target)) {
            throw new IllegalArgumentException("조건 모델에는 노선 기준과 일치하는 대상 정류장 방향이 필요하다");
        }
        double[] columns = Arrays.copyOf(original, CONDITIONAL_NAMES.size());
        columns[3] = 0; // 학습에서 사용하지 않은 신규 시간대와 과거 통계 입력.
        if (this == STOP_DIRECTION_TIME) {
            Arrays.fill(columns, 28, 31, 0);
        }
        final double position = columns[19];
        for (int index = 0; index < BASIS_COUNT; index++) {
            final double knot = index / (double) (BASIS_COUNT - 1);
            columns[BASIS_START + index] = Math.max(1 - Math.abs(position - knot) * (BASIS_COUNT - 1), 0);
        }
        columns[DOWN_COLUMN] = directionFeature(target.direction());
        int destination = DOWN_COLUMN + 1;
        for (final int timeColumn : new int[] {1, 2}) {
            for (int index = 0; index < BASIS_COUNT; index++) {
                columns[destination++] = columns[BASIS_START + index] * columns[timeColumn];
            }
            columns[destination++] = columns[DOWN_COLUMN] * columns[timeColumn];
        }
        return columns;
    }

    private static List<String> conditionalNames() {
        List<String> names = new ArrayList<>(SeatForecastDesignMatrix.COLUMN_NAMES);
        names.add("target_direction_down");
        for (String time : List.of("morning", "evening")) {
            for (int index = 0; index < BASIS_COUNT; index++) {
                names.add(time + "_stop_position_basis_" + index);
            }
            names.add(time + "_target_direction_down");
        }
        return List.copyOf(names);
    }

    private static double directionFeature(RouteDirection direction) {
        if (direction == RouteDirection.DOWN) {
            return 1;
        }
        return 0;
    }
}
