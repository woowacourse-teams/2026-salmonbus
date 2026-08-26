package com.gustler.backend.processor;

import java.util.Arrays;

public record SeatDistribution(
    double[] probabilities
) {

    private static final double SUM_TOLERANCE = 1e-9;

    public SeatDistribution {
        if (probabilities == null || probabilities.length == 0) {
            throw new IllegalArgumentException("좌석 수별 확률이 하나도 없으면 분포가 아니다");
        }
        for (final double probability : probabilities) {
            if (!(probability >= 0.0 && probability <= 1.0)) {
                throw new IllegalArgumentException("좌석 수별 확률은 0과 1 사이의 수다: " + probability);
            }
        }
        final double sum = sum(probabilities);
        if (Math.abs(sum - 1.0) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("좌석 수별 확률을 모두 더하면 1이어야 한다: " + sum);
        }
        probabilities = probabilities.clone();
    }

    public static SeatDistribution of(
        final double... probabilities
    ) {
        return new SeatDistribution(probabilities);
    }

    public double pFull() {
        return probabilities[0];
    }

    public double expectedSeats() {
        double expected = 0.0;
        for (int seats = 0; seats < probabilities.length; seats++) {
            expected += seats * probabilities[seats];
        }
        return expected;
    }

    @Override
    public double[] probabilities() {
        return probabilities.clone();
    }

    @Override
    public boolean equals(
        final Object other
    ) {
        return other instanceof SeatDistribution distribution
            && Arrays.equals(probabilities, distribution.probabilities);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(probabilities);
    }

    @Override
    public String toString() {
        return "SeatDistribution" + Arrays.toString(probabilities);
    }

    private static double sum(
        final double[] probabilities
    ) {
        double sum = 0.0;
        for (final double probability : probabilities) {
            sum += probability;
        }
        return sum;
    }
}
