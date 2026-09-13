package com.gustler.backend.processor.seatdistribution;

/**
 * 노선 하나 · 예보 거리 하나에 붙는 계수 묶음.
 *
 * <p>예보 하나가 쓰는 계수는 전부 여기 있다. 노선과 거리로 고르는 것은 계수 묶음 쪽 일이고,
 * 고른 다음부터는 이 값만 본다.
 *
 * <p>특징 개수를 상수로 안 박는다. 계수 길이가 곧 그 번들이 요구하는 특징 개수이고,
 * 그 수는 특징 계약이 정하는 것이지 이 코드가 정하는 것이 아니다.
 */
final class HorizonCoefficients {

    /** 크기 묶음 수. 상대 경계 개수와 같다. */
    static final int BIN_COUNT = 9;

    private final double[] fullChance;
    private final double[] anchor;
    private final double[] sameSeats;
    private final double[] belowAnchor;
    private final double[][][] bins;
    private final boolean[][] binFitted;

    HorizonCoefficients(
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

    int featureCount() {
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
