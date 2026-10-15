package com.gustler.backend.arrivals.domain;

import java.time.Duration;

/** 단조 시각으로 계산하는 호출 속도 제한. 동시 접근의 직렬화는 호출자가 담당한다. */
public final class ArrivalRateLimit {
    private final double callsPerSecond;
    private final int burst;
    private long lastRefill;
    private double tokens;

    public ArrivalRateLimit(double callsPerSecond, int burst, long startedAtNanos) {
        this.callsPerSecond = callsPerSecond;
        this.burst = burst;
        this.lastRefill = startedAtNanos;
        this.tokens = burst;
    }

    public Duration acquire(long nowNanos) {
        tokens = Math.min(burst, tokens + Math.max(0, nowNanos - lastRefill) / 1e9 * callsPerSecond);
        lastRefill = nowNanos;
        if (tokens >= 1) {
            tokens--;
            return Duration.ZERO;
        }
        return Duration.ofNanos((long) Math.ceil((1 - tokens) / callsPerSecond * 1e9));
    }
}
