package com.gustler.backend.processor;

import java.util.Arrays;

public record SeatDistribution(
    double[] chanceBySeats
) {

    private static final double PROBABILITY_SUM_TOLERANCE = 1e-9;

    public SeatDistribution {
        if (chanceBySeats == null || chanceBySeats.length == 0) {
            throw new IllegalArgumentException("좌석 수별 확률이 하나도 없으면 분포가 아니다");
        }
        for (final double probability : chanceBySeats) {
            if (!(probability >= 0.0 && probability <= 1.0)) {
                throw new IllegalArgumentException("좌석 수별 확률은 0과 1 사이의 수다: " + probability);
            }
        }
        final double sum = sum(chanceBySeats);
        if (Math.abs(sum - 1.0) > PROBABILITY_SUM_TOLERANCE) {
            throw new IllegalArgumentException("좌석 수별 확률을 모두 더하면 1이어야 한다: " + sum);
        }
        chanceBySeats = chanceBySeats.clone();
    }

    public static SeatDistribution of(
        final double... chanceBySeats
    ) {
        return new SeatDistribution(chanceBySeats);
    }

    public double fullChance() {
        return chanceBySeats[0];
    }

    public double expectedSeats() {
        double expected = 0.0;
        for (int seats = 0; seats < chanceBySeats.length; seats++) {
            expected += seats * chanceBySeats[seats];
        }
        return expected;
    }

    @Override
    public double[] chanceBySeats() {
        return chanceBySeats.clone();
    }

    @Override
    public boolean equals(
        final Object other
    ) {
        return other instanceof SeatDistribution distribution
            && Arrays.equals(chanceBySeats, distribution.chanceBySeats);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(chanceBySeats);
    }

    @Override
    public String toString() {
        return "SeatDistribution" + Arrays.toString(chanceBySeats);
    }

    private static double sum(
        final double[] chanceBySeats
    ) {
        double sum = 0.0;
        for (final double probability : chanceBySeats) {
            sum += probability;
        }
        return sum;
    }
}
