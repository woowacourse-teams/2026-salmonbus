package com.gustler.backend.api.arrival.application;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/** 최초 수신 대기의 시간과 동시 요청 스레드 점유를 제한한다. */
public final class ArrivalFirstResultPolicy {
    private final Duration maximumWait;
    private final Semaphore slots;

    public ArrivalFirstResultPolicy(Duration maximumWait, final int maximumWaiters) {
        if (maximumWait == null || maximumWait.isNegative()
            || maximumWait.compareTo(Duration.ofMillis(ArrivalRefresh.MAX_WAIT_MILLIS)) > 0
            || maximumWaiters < 1 || maximumWaiters > 64) {
            throw new IllegalArgumentException("최초 ETA 대기의 시간·동시 요청 한도가 올바르지 않다");
        }
        this.maximumWait = maximumWait;
        this.slots = new Semaphore(maximumWaiters);
    }

    public Duration maximumWait() { return maximumWait; }
    public boolean tryAcquire() { return !maximumWait.isZero() && slots.tryAcquire(); }
    public void release() { slots.release(); }
}
