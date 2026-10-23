package com.gustler.backend.forecasting.domain.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 특징 벡터 하나를 좌석 71칸의 확률로 변환한다.
 *
 * <p>만석 확률 계산, 당일 성적 보정, 분포의 중심 좌석 계산, 중심과의 잔차 계산,
 * 잔차를 좌석 범위로 변환하는 다섯 단계를 수행한다.
 *
 * <p>계수는 생성 시 전달받으며 이후 변경하지 않는다. 모델 활성화로 계수가 바뀌면
 * 새 예측기를 만든다. 처리 중인 배치는 기존 예측기를 유지해 처음부터 끝까지 같은 계수를 사용한다.
 */
public final class SeatDistributionPredictor {
    private final CoefficientLookup coefficients;
    private final double[] relativeEdges;

    public SeatDistributionPredictor(
        CoefficientLookup coefficients,
        double[] relativeEdges
    ) {
        this.coefficients = coefficients;
        this.relativeEdges = relativeEdges.clone();
    }

    /**
     * 번들이 요구하는 특징 길이를 검증하고 좌석별 확률을 계산한다. 당일 보정 확률과 원래 만석 확률을 구별해 반환한다.
     */
    public SeatForecastResult predict(
        SeatDistributionInput input
    ) {
        HorizonCoefficients cell = coefficients.at(input.modelRoute(), input.stopsAhead());
        double[] featureVector = input.featureVector();
        if (cell.featureCount() != featureVector.length) {
            throw new IllegalArgumentException(
                "번들이 요구하는 특징 개수와 들어온 벡터의 길이가 다르다: %d, %d"
                .formatted(cell.featureCount(), featureVector.length)
            );
        }

        final double rawFullChance = cell.hurdle().rawFullChanceOf(featureVector);
        final double fullChance = FullChancePriorShift.shifted(rawFullChance, input.sameDayOutcomes());
        final int anchorSeats = cell.anchor().seatsOf(input.currentSeats(), input.capacity());
        double[] residuals = cell.residuals(relativeEdges)
            .chancesByResidual(featureVector, anchorSeats, input.capacity());
        double[] chances = SeatProbabilityTable.of(
            residuals, anchorSeats, input.capacity(), fullChance);

        return new SeatForecastResult(new SeatDistribution(boxed(chances)), rawFullChance);
    }

    private static List<Double> boxed(
        double[] chances
    ) {
        return Arrays.stream(chances)
            .boxed()
            .collect(Collectors.toCollection(() -> new ArrayList<>(chances.length)));
    }
}
