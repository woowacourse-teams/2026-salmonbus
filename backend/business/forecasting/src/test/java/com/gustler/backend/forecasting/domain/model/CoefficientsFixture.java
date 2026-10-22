package com.gustler.backend.forecasting.domain.model;

/**
 * 테스트에서 계수를 직접 구성한다.
 *
 * <p>특징 벡터를 {@code {1.0}} 한 칸으로 두면 계수 하나가 곧 로짓이 된다. 원하는
 * 확률을 로짓으로 입력하면 해당 확률이 그대로 계산되므로 계산식보다 규칙을 검증하는 테스트에서
 * 값을 쉽게 확인할 수 있다.
 */
public final class CoefficientsFixture {
    static final double[] ONE_FEATURE = {1.0};

    public static final double[] RELATIVE_EDGES =
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
