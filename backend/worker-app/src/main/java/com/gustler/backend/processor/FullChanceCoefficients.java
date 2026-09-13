package com.gustler.backend.processor;

import java.util.List;

/**
 * 만석 확률을 내는 계수 한 묶음. 설계행렬 열마다 계수 하나다.
 *
 * <p>A18 의 1단계다. 좌석이 몇 석 남을지를 보기 전에 <b>만석인지 아닌지만 먼저 가른다.</b>
 * 문서는 이 단계를 허들이라고 적었다.
 *
 * <p><b>계수를 주입받는 자리가 여기다.</b> 계수는 서버가 만들지 않는다. 밖에서 배운 것을 받아
 * 쓴다. 노선 2개와 지평 12개마다 따로 배워서 묶음이 24개고 이 단계에만 실수가 744개다.
 * 그 묶음을 (노선 판본, 지평)으로 찾아 주는 포트는 계수를 적재하고 승격하는 쪽이 만든다.
 * 그때까지 이 값은 손으로 만들어 넣는다.
 *
 * <p>계수가 없으면 만석 확률을 낼 수 없다. 그래서 지금 {@link SeatForecastModel} 구현체가
 * 하나도 없고 예보 배치가 batch 를 하나도 안 연다.
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
     * <p><b>그날 회수한 라벨로 보정하기 전 값이다.</b> 보정하는 층은 아직 없다. seat_forecast 의
     * seat_full_chance_raw 열과 {@link SeatForecastResult#fullChanceRaw()} 가 같은 값을 든다.
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
