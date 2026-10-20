package com.gustler.backend.forecasting.domain.model;

import java.util.Objects;

/**
 * 좌석 확률 분포 계산의 결과다.
 * 만석 확률은 같은 분포의 0석 확률을 사용한다.
 */
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
