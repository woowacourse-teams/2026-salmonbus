package com.gustler.backend.processor;

public record SeatForecastResult(
    SeatDistribution distribution,
    double pFullRaw
) {

    public SeatForecastResult {
        if (!(pFullRaw >= 0.0 && pFullRaw <= 1.0)) {
            throw new IllegalArgumentException("보정 전 만석 확률은 0과 1 사이의 수다: " + pFullRaw);
        }
    }

    public double pFull() {
        return distribution.pFull();
    }
}
