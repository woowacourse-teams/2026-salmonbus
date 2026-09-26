package com.gustler.backend.forecasting.domain.model;

import java.util.List;

/**
 * 만석 확률을 계산하는 계수. 설계행렬의 각 열에 계수 하나가 대응한다.
 *
 * <p>A18 의 1단계다. 좌석이 몇 석 남을지를 보기 전에 <b>만석인지 아닌지만 먼저 가른다.</b>
 * 문서는 이 단계를 허들이라고 적었다.
 *
 * <p>서버에서 계수를 학습하지 않고 외부 학습 결과를 받아 사용한다.
 * 노선 2개와 예보 거리 12개에 각각 학습하므로 계수 집합은 24개이며 이 단계의 실수 계수는 744개다.
 * 이 객체는 전달받은 열별 계수로 보정 전 확률을 계산한다.
 */
public record FullChanceCoefficients(
    List<Double> byColumn
) {

    public FullChanceCoefficients {
        byColumn = List.copyOf(byColumn);
        if (byColumn.size() != SeatForecastDesignMatrix.COLUMN_COUNT) {
            throw new IllegalArgumentException(
                "계수는 설계행렬 열 수와 같은 %d개다: %d".formatted(SeatForecastDesignMatrix.COLUMN_COUNT, byColumn.size())
            );
        }
    }

    /**
     * 설계행렬 한 줄에 이 계수를 적용한 만석 확률.
     *
     * <p><b>당일 평가 결과로 보정하기 전 확률이다.</b> 실제 예측 경로의 당일 보정은
     * {@link SeatDistributionPredictor}에서 별도로 수행한다. seat_forecast의
     * seat_full_chance_raw 열과 {@link SeatForecastResult#fullChanceRaw()}는 보정 전 확률을 담는다.
     */
    public double fullChanceBeforeCorrectionOf(
        SeatForecastDesignMatrix matrix
    ) {
        double weightedSum = 0;
        for (int columnNumber = 1; columnNumber <= SeatForecastDesignMatrix.COLUMN_COUNT; columnNumber++) {
            weightedSum += byColumn.get(columnNumber - 1) * matrix.columnAt(columnNumber);
        }
        return logisticOf(weightedSum);
    }

    /** 실수 하나를 0과 1 사이 확률로 옮긴다. 0이면 0.5다. */
    private static double logisticOf(
        final double weightedSum
    ) {
        return 1 / (1 + Math.exp(-weightedSum));
    }
}
