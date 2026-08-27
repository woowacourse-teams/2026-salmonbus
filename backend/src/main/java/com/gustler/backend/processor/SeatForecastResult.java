package com.gustler.backend.processor;

public record SeatForecastResult(
    SeatDistribution distribution,
    double fullChanceRaw
) {

    public SeatForecastResult {
        if (!(fullChanceRaw >= 0.0 && fullChanceRaw <= 1.0)) {
            throw new IllegalArgumentException("보정 전 만석 확률은 0과 1 사이의 수다: " + fullChanceRaw);
        }
    }

    public double fullChance() {
        return distribution.fullChance();
    }
}
