package com.gustler.backend.forecasting.api;

import java.time.Duration;

public record ForecastPolicy(Duration staleness, int batchLimit, int pendingLimit, int arrivalLimit) {
    public static final Duration MAX_STALENESS = Duration.ofHours(1);

    public ForecastPolicy {
        if (staleness == null || staleness.isNegative() || staleness.isZero()
            || staleness.compareTo(MAX_STALENESS) > 0) {
            throw new IllegalArgumentException("예보 신선도는 0초 초과, 1시간 이하여야 한다");
        }
        if (batchLimit < 1 || pendingLimit < 1 || arrivalLimit < 1) {
            throw new IllegalArgumentException("예보 작업의 처리 한도는 1 이상이어야 한다");
        }
    }
}
