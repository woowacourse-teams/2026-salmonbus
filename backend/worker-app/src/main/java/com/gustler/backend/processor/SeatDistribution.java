package com.gustler.backend.processor;

import java.util.List;
import java.util.stream.IntStream;

public record SeatDistribution(
    List<Double> chanceBySeats
) {

    private static final double PROBABILITY_SUM_TOLERANCE = 1e-9;
    private static final int NO_SEAT_LEFT = 0;

    public SeatDistribution {
        chanceBySeats = List.copyOf(chanceBySeats);
        validate(chanceBySeats);
    }

    public double fullChance() {
        return chanceOf(NO_SEAT_LEFT);
    }

    public double chanceOf(
        final int seatsLeft
    ) {
        return chanceBySeats.get(seatsLeft);
    }

    public double expectedSeats() {
        return IntStream.range(0, chanceBySeats.size())
            .mapToDouble(seatsLeft -> seatsLeft * chanceOf(seatsLeft))
            .sum();
    }

    static boolean isBetweenZeroAndOne(
        final double chance
    ) {
        return chance >= 0.0 && chance <= 1.0;
    }

    private static void validate(
        List<Double> chanceBySeats
    ) {
        chanceBySeats.stream()
            .filter(chance -> !isBetweenZeroAndOne(chance))
            .findFirst()
            .ifPresent(chance -> {
                throw new IllegalArgumentException(
                    "0석 남을 확률부터 차례로 담는다. 각 값은 0과 1 사이여야 한다: " + chance
                );
            });

        final double sum = chanceBySeats.stream()
            .mapToDouble(Double::doubleValue)
            .sum();
        if (Math.abs(sum - 1.0) > PROBABILITY_SUM_TOLERANCE) {
            throw new IllegalArgumentException(
                "0석부터 차례로 담은 확률을 모두 더하면 1이어야 한다: " + sum
            );
        }
    }
}
