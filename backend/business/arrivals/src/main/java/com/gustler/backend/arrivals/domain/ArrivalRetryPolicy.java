package com.gustler.backend.arrivals.domain;

import java.time.Duration;

/** 연속 실패에 따른 대기와 상류의 Retry-After 중 긴 쪽을 사용한다. */
public record ArrivalRetryPolicy(Duration base, Duration maximum) {
    /**
     * 연속 실패 횟수에 따라 지수 대기 시간을 제한한다. 원천 Retry-After가 더 길면 그 시간을 우선한다.
     */
    public Duration delay(final int consecutiveFailures, Duration retryAfter) {
        final long factor = 1L << Math.min(Math.max(0, consecutiveFailures - 1), 20);
        Duration delay = base.multipliedBy(factor);
        if (delay.compareTo(maximum) > 0) {
            delay = maximum;
        }
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        return delay;
    }
}
