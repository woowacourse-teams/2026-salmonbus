package com.gustler.backend.forecasting.api.evaluation;

import java.time.Duration;

/**
 * 당일 합계 초기화의 처리와 재시도 간격을 제한하는 정책 값이다.
 */
public record SameDayInitializationPolicy(Duration retryInterval, Duration statementTimeout, Duration lockTimeout) {
    private static final Duration SHORTEST = Duration.ofMillis(1);

    public SameDayInitializationPolicy {
        if (hasInvalidDuration(retryInterval, statementTimeout, lockTimeout)) {
            throw new IllegalArgumentException("당일 성적 초기화의 재시도 간격과 시간 제한은 1ms 이상이어야 한다");
        }
    }

    private static boolean hasInvalidDuration(
        Duration retryInterval,
        Duration statementTimeout,
        Duration lockTimeout
    ) {
        if (tooShort(retryInterval) || tooShort(statementTimeout)) {
            return true;
        }
        return tooShort(lockTimeout);
    }

    private static boolean tooShort(Duration value) {
        return value == null || value.compareTo(SHORTEST) < 0;
    }
}
