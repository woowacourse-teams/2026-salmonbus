package com.gustler.backend.forecasting.api.statistics;

import java.time.Duration;

/**
 * 노선 통계의 갱신 간격을 전달한다. 0보다 큰 Duration만 허용한다.
 */
public record DemandStatisticsPolicy(Duration refreshInterval) {
    public DemandStatisticsPolicy {
        if (!isPositive(refreshInterval)) {
            throw new IllegalArgumentException("통계 주기는 양수여야 한다");
        }
    }

    private static boolean isPositive(Duration refreshInterval) {
        if (refreshInterval == null || refreshInterval.isNegative()) {
            return false;
        }
        return !refreshInterval.isZero();
    }
}
