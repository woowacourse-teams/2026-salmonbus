package com.gustler.backend.forecasting.domain.model;

/**
 * 노선 하나 · 예보 거리 하나의 계수 묶음.
 *
 * <p>예보 하나에 필요한 계수를 모두 보관한다. 계수 묶음이 노선과 예보 거리로 이 값을 선택하면
 * 이후 계산에서는 선택된 계수만 사용한다.
 *
 * <p>특징 개수는 번들의 특징 계약에 따라 달라지므로 상수로 고정하지 않는다.
 * 번들이 요구하는 특징 개수는 계수 길이로 확인한다.
 */
public final class HorizonCoefficients {
    /** 크기 묶음 수. 상대 경계 개수와 같다. */
    public static final int BIN_COUNT = 9;

    private final double[] fullChance;
    private final double[] anchor;
    private final double[] sameSeats;
    private final double[] belowAnchor;
    private final double[][][] bins;
    private final boolean[][] binFitted;

    public HorizonCoefficients(
        double[] fullChance,
        double[] anchor,
        double[] sameSeats,
        double[] belowAnchor,
        double[][][] bins,
        boolean[][] binFitted
    ) {
        this.fullChance = fullChance;
        this.anchor = anchor;
        this.sameSeats = sameSeats;
        this.belowAnchor = belowAnchor;
        this.bins = bins;
        this.binFitted = binFitted;
    }

    public int featureCount() {
        return fullChance.length;
    }

    FullChanceHurdle hurdle() {
        return new FullChanceHurdle(fullChance);
    }

    SeatAnchor anchor() {
        return new SeatAnchor(anchor[0], anchor[1]);
    }

    ResidualDistribution residuals(
        double[] relativeEdges
    ) {
        return new ResidualDistribution(sameSeats, belowAnchor, bins, binFitted, relativeEdges);
    }
}
