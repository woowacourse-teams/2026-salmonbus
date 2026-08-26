package com.gustler.backend.processor;

public record SeatForecastResult(
    SeatDistribution distribution,
    double pFullRaw
) {

    public double pFull() {
        return distribution.pFull();
    }
}
