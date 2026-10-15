package com.gustler.backend.arrivals.api;

import java.time.Duration;

public record ArrivalCollectionPolicy(Duration refreshInterval, Duration validity, Duration retryBase,
    Duration retryMaximum, Duration startupDelay, Duration queueWait, int maximumStates,
    double callsPerSecond, int burst, int saveAttempts) {
    public ArrivalCollectionPolicy {
        if (refreshInterval == null || refreshInterval.isNegative() || refreshInterval.isZero()
            || validity == null || validity.compareTo(refreshInterval) <= 0
            || retryBase == null || retryBase.isNegative() || retryBase.isZero()
            || retryMaximum == null || retryMaximum.compareTo(retryBase) < 0
            || startupDelay == null || startupDelay.compareTo(refreshInterval) < 0
            || queueWait == null || queueWait.isNegative() || queueWait.isZero()
            || maximumStates < 1 || !Double.isFinite(callsPerSecond) || callsPerSecond <= 0
            || burst < 1 || saveAttempts < 1 || saveAttempts > 3) {
            throw new IllegalArgumentException("도착 수집의 시간·용량·예산 설정이 올바르지 않다");
        }
    }

    public static ArrivalCollectionPolicy defaults() {
        return new ArrivalCollectionPolicy(Duration.ofSeconds(40), Duration.ofSeconds(60),
            Duration.ofSeconds(40), Duration.ofSeconds(300), Duration.ofSeconds(40),
            Duration.ofSeconds(2), 10_000, 40_000.0 / 86_400, 8, 2);
    }
}
