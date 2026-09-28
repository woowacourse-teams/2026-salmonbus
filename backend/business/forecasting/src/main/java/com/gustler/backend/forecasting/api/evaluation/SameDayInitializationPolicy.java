package com.gustler.backend.forecasting.api.evaluation;

import java.time.Duration;

public record SameDayInitializationPolicy(Duration retryInterval, Duration statementTimeout, Duration lockTimeout) {

    private static final Duration SHORTEST = Duration.ofMillis(1);

    public SameDayInitializationPolicy {
        if (tooShort(retryInterval) || tooShort(statementTimeout) || tooShort(lockTimeout)) {
            throw new IllegalArgumentException("당일 성적 초기화의 재시도 간격과 시간 제한은 1ms 이상이어야 한다");
        }
    }

    private static boolean tooShort(final Duration value) {
        return value == null || value.compareTo(SHORTEST) < 0;
    }
}
