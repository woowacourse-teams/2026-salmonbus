package com.gustler.backend.forecasting.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 재생이 정한 시각을 돌려주는 시계다. 예보는 배치를 처리하기 시작할 때 이 시계로 착수 시각을 찍으므로, 배치를 넣기 전에 그 배치의
 * 착수 시각으로 맞춘다. 한 스레드에서만 쓴다.
 */
final class WorkerReplayClock extends Clock {
    private Instant now = Instant.EPOCH;

    void moveTo(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
