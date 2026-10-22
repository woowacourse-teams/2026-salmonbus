package com.gustler.backend.member.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** 테스트가 시각을 앞으로 옮길 수 있는 시계. 여러 스레드가 같은 시각을 읽는다. */
public class AdjustableClock extends Clock {
    public static final Instant INITIAL = Instant.parse("2026-10-21T00:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(INITIAL);
    private final ZoneId zone;

    public AdjustableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    public void reset() {
        now.set(INITIAL);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("테스트 시계는 시간대를 바꾸지 않는다");
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
