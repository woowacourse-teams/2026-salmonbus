package com.gustler.backend.forecasting.api;

import java.time.Duration;

/** 예보할 배치의 유효 시간과 한 번에 조회할 최대 배치 수를 정한다. */
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
