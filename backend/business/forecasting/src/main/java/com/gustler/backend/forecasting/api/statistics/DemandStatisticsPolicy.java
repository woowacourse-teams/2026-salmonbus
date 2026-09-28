package com.gustler.backend.forecasting.api.statistics;

import java.time.Duration;

public record DemandStatisticsPolicy(Duration refreshInterval) {

    public DemandStatisticsPolicy {
        if (refreshInterval == null || refreshInterval.isNegative() || refreshInterval.isZero()) {
            throw new IllegalArgumentException("통계 주기는 양수여야 한다");
        }
    }
}
