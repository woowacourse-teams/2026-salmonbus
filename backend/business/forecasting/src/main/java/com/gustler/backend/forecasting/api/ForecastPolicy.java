package com.gustler.backend.forecasting.api;

import java.time.Duration;

/**
 * 예보가 배치를 줍는 신선도 창과 한 번에 줍는 배치 수의 한도를 전달한다.
 */
public record ForecastPolicy(Duration staleness, int batchLimit) {

    public static final Duration MAX_STALENESS = Duration.ofHours(1);

    public ForecastPolicy {
        if (!hasValidStaleness(staleness)) {
            throw new IllegalArgumentException("예보 신선도는 0초 초과, 1시간 이하여야 한다");
        }
        if (batchLimit < 1) {
            throw new IllegalArgumentException("예보 작업의 처리 한도는 1 이상이어야 한다");
        }
    }

    private static boolean hasValidStaleness(Duration staleness) {
        if (staleness == null || staleness.isNegative()) {
            return false;
        }
        if (staleness.isZero()) {
            return false;
        }
        return staleness.compareTo(MAX_STALENESS) <= 0;
    }
}
