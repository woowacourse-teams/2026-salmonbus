package com.gustler.backend.arrivals.api;

import java.time.Duration;

/**
 * 정류장 수집의 간격·대기·재시도·보존·실행 한도를 함께 전달하는 정책 값이다.
 */
public record ArrivalCollectionPolicy(Duration refreshInterval, Duration validity, Duration retryBase,
    Duration retryMaximum, Duration startupDelay, Duration queueWait, int maximumStates,
    double callsPerSecond, int burst, int saveAttempts) {
    public ArrivalCollectionPolicy {
        if (isInvalidRefreshWindow(refreshInterval, validity)) {
            throw invalidPolicy();
        }
        if (isInvalidRetryWindow(retryBase, retryMaximum)) {
            throw invalidPolicy();
        }
        if (isInvalidSchedulingDelay(startupDelay, refreshInterval) || isInvalidDuration(queueWait)) {
            throw invalidPolicy();
        }
        if (isInvalidExecutionLimit(maximumStates, callsPerSecond)) {
            throw invalidPolicy();
        }
        if (burst < 1 || isInvalidSaveAttempts(saveAttempts)) {
            throw invalidPolicy();
        }
    }

    private static boolean isInvalidRefreshWindow(Duration refreshInterval, Duration validity) {
        if (isInvalidDuration(refreshInterval) || validity == null) {
            return true;
        }
        return validity.compareTo(refreshInterval) <= 0;
    }

    private static boolean isInvalidRetryWindow(Duration retryBase, Duration retryMaximum) {
        if (isInvalidDuration(retryBase) || retryMaximum == null) {
            return true;
        }
        return retryMaximum.compareTo(retryBase) < 0;
    }

    private static boolean isInvalidSchedulingDelay(Duration startupDelay, Duration refreshInterval) {
        return startupDelay == null || startupDelay.compareTo(refreshInterval) < 0;
    }

    private static boolean isInvalidDuration(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return true;
        }
        return duration.isZero();
    }

    private static boolean isInvalidExecutionLimit(int maximumStates, double callsPerSecond) {
        if (maximumStates < 1 || !Double.isFinite(callsPerSecond)) {
            return true;
        }
        return callsPerSecond <= 0;
    }

    private static boolean isInvalidSaveAttempts(int saveAttempts) {
        return saveAttempts < 1 || saveAttempts > 3;
    }

    private static IllegalArgumentException invalidPolicy() {
        return new IllegalArgumentException("도착 수집의 시간·용량·예산 설정이 올바르지 않다");
    }

    public static ArrivalCollectionPolicy defaults() {
        return new ArrivalCollectionPolicy(Duration.ofSeconds(40), Duration.ofSeconds(60),
            Duration.ofSeconds(40), Duration.ofSeconds(300), Duration.ofSeconds(40),
            Duration.ofSeconds(2), 10_000, 40_000.0 / 86_400, 8, 2);
    }
}
