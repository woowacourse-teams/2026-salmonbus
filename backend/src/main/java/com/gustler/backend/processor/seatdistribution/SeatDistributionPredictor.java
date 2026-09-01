package com.gustler.backend.processor.seatdistribution;

import com.gustler.backend.processor.SeatDistribution;
import com.gustler.backend.processor.SeatForecastResult;
import java.util.ArrayList;
import java.util.List;

/**
 * 특징 벡터 하나를 좌석 71칸 확률로 옮긴다.
 *
 * <p>다섯 걸음이다. 만석 확률을 내고, 당일 성적으로 옮기고, 분포의 중심 좌석을 잡고,
 * 중심에서 얼마나 어긋날지를 내고, 그 잔차를 좌석으로 옮겨 접는다.
 *
 * <p>계수 묶음을 들고 있고 바뀌지 않는다. 승격으로 계수가 바뀌면 새 예측기가 생기지
 * 이 예측기의 계수가 갈리지 않는다. 도는 batch 하나가 처음부터 끝까지 같은 계수를 쓰는 것이
 * 그 위에 선다.
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

    public SeatForecastResult predict(
        SeatDistributionInput input
    ) {
        HorizonCoefficients cell = coefficients.at(input.modelRoute(), input.stopsAhead());
        if (cell.featureCount() != input.featureVector().length) {
            throw new IllegalArgumentException(
                "번들이 요구하는 특징 개수와 들어온 벡터의 길이가 다르다: %d, %d"
                    .formatted(cell.featureCount(), input.featureVector().length)
            );
        }

        final double rawFullChance = cell.hurdle().rawFullChanceOf(input.featureVector());
        final double fullChance = FullChancePriorShift.shifted(rawFullChance, input.sameDayOutcomes());
        final int anchorSeats = cell.anchor().seatsOf(input.currentSeats(), input.capacity());
        double[] residuals = cell.residuals(relativeEdges)
            .chancesByResidual(input.featureVector(), anchorSeats, input.capacity());
        double[] chances = SeatProbabilityTable.of(
            residuals, anchorSeats, input.capacity(), fullChance);

        return new SeatForecastResult(new SeatDistribution(boxed(chances)), rawFullChance);
    }

    private static List<Double> boxed(
        double[] chances
    ) {
        List<Double> boxed = new ArrayList<>(chances.length);
        for (final double chance : chances) {
            boxed.add(chance);
        }
        return boxed;
    }
}
