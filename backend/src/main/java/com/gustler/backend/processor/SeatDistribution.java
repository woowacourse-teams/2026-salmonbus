package com.gustler.backend.processor;

import java.util.List;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public record SeatDistribution(
    List<Double> chanceBySeats
) {

    private static final double PROBABILITY_SUM_TOLERANCE = 1e-9;

    public SeatDistribution {
        chanceBySeats = List.copyOf(chanceBySeats);
        validate(chanceBySeats);
    }

    public static SeatDistribution of(
        final double fullChance,
        final double... restChances
    ) {
        return new SeatDistribution(
            DoubleStream.concat(DoubleStream.of(fullChance), DoubleStream.of(restChances))
                .boxed()
                .toList()
        );
    }

    public double fullChance() {
        return chanceBySeats.getFirst();
    }

    public double expectedSeats() {
        return IntStream.range(0, chanceBySeats.size())
            .mapToDouble(seats -> seats * chanceBySeats.get(seats))
            .sum();
    }

    static boolean isChance(
        final double chance
    ) {
        return chance >= 0.0 && chance <= 1.0;
    }

    private static void validate(
        final List<Double> chanceBySeats
    ) {
        for (final double chance : chanceBySeats) {
            if (!isChance(chance)) {
                throw new IllegalArgumentException(
                    "0석 남을 확률부터 차례로 담는다. 각 값은 0과 1 사이여야 한다: " + chance
                );
            }
        }
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
