package com.gustler.backend.processor;

import java.util.Objects;

public record SeatForecastResult(
    SeatDistribution distribution,
    double fullChanceRaw
) {

    public SeatForecastResult {
        Objects.requireNonNull(distribution, "예보에는 좌석 분포가 있어야 한다");
        if (!SeatDistribution.isBetweenZeroAndOne(fullChanceRaw)) {
            throw new IllegalArgumentException("보정 전 만석 확률은 0과 1 사이의 수다: " + fullChanceRaw);
        }
    }

    public double fullChance() {
        return distribution.fullChance();
    }
}
