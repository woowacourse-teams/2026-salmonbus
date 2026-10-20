package com.gustler.backend.arrivals.domain;

import java.time.Duration;

/** 단조 시각으로 계산하는 호출 속도 제한. 동시 접근의 직렬화는 호출자가 담당한다. */
public final class ArrivalRateLimit {
    private final double callsPerSecond;
    private final int burst;
    private long lastRefill;
    private double tokens;

    public ArrivalRateLimit(final double callsPerSecond, final int burst, final long startedAtNanos) {
        this.callsPerSecond = callsPerSecond;
        this.burst = burst;
        this.lastRefill = startedAtNanos;
        this.tokens = burst;
    }

    /**
     * 단조 증가 나노초 시각으로 토큰을 보충한다. 허용되면 토큰을 소비하고 0을, 부족하면 다음 허용까지의 대기 시간을 반환한다.
     */
    public Duration acquire(final long nowNanos) {
        tokens = Math.min(burst, tokens + Math.max(0, nowNanos - lastRefill) / 1e9 * callsPerSecond);
        lastRefill = nowNanos;
        if (tokens >= 1) {
            tokens--;
            return Duration.ZERO;
        }
        return Duration.ofNanos((long) Math.ceil((1 - tokens) / callsPerSecond * 1e9));
    }
}
