package com.gustler.backend.api.salmon.offline;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 판정 시각으로 옮겨 놓는 시계. 제품 시계와 같은 시간대를 쓴다. */
final class AsOfClock extends Clock {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private Instant at;

    AsOfClock(Instant at) {
        this.at = at;
    }

    void moveTo(Instant instant) {
        at = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("판정 시각 시계는 시간대를 바꾸지 않는다");
    }

    @Override
    public Instant instant() {
        return at;
    }
}
