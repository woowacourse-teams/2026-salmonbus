package com.gustler.backend.processor.seatdistribution;

/**
 * 테스트가 계수를 손으로 세우는 자리.
 *
 * <p>특징 벡터를 {@code {1.0}} 한 칸으로 두면 계수 하나가 곧 로짓이 된다. 그래서 원하는
 * 확률을 로짓으로 적어 넣으면 그 확률이 그대로 나온다. 계산이 아니라 규칙을 보는 테스트에서
 * 값을 눈으로 따라갈 수 있다.
 */
final class CoefficientsFixture {

    static final double[] ONE_FEATURE = {1.0};

    static final double[] RELATIVE_EDGES =
        {0.0, 0.03, 0.07, 0.12, 0.2, 0.32, 0.48, 0.7, 1.0};

    private CoefficientsFixture() {
    }

    /** 특징 벡터가 {@code {1.0}} 일 때 이 확률을 내는 계수 한 칸. */
    static double[] chancing(
        final double chance
    ) {
        return new double[] {Math.log(chance / (1.0 - chance))};
    }

    static boolean[] allFitted() {
        return new boolean[] {true, true, true, true, true, true, true, true, true};
    }

    static boolean[] noneFitted() {
        return new boolean[9];
    }

    static boolean[] onlyFirstFitted() {
        boolean[] fitted = new boolean[9];
        fitted[0] = true;
        return fitted;
    }

    static double[][] evenBins(
        final double chance
    ) {
        double[][] bins = new double[9][];
        for (int index = 0; index < bins.length; index++) {
            bins[index] = chancing(chance);
        }
        return bins;
    }

    static ResidualDistribution residuals(
        final double sameSeatsChance,
        final double belowAnchorChance,
        boolean[] belowAnchorFitted,
        boolean[] aboveAnchorFitted
    ) {
        return new ResidualDistribution(
            chancing(sameSeatsChance),
            chancing(belowAnchorChance),
            new double[][][] {evenBins(0.5), evenBins(0.5)},
            new boolean[][] {belowAnchorFitted, aboveAnchorFitted},
            RELATIVE_EDGES);
    }

    static HorizonCoefficients horizon(
        final double fullChance,
        final double anchorIntercept,
        final double anchorSlope,
        final double sameSeatsChance,
        final double belowAnchorChance
    ) {
        return new HorizonCoefficients(
            chancing(fullChance),
            new double[] {anchorIntercept, anchorSlope},
            chancing(sameSeatsChance),
            chancing(belowAnchorChance),
            new double[][][] {evenBins(0.5), evenBins(0.5)},
            new boolean[][] {allFitted(), allFitted()});
    }

    static CoefficientLookup lookupOf(
        HorizonCoefficients coefficients
    ) {
        return (modelRoute, stopsAhead) -> coefficients;
    }
}
