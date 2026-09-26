package com.gustler.backend.forecasting.domain.quality;

import java.time.Instant;
import java.util.Objects;

/** 수동 조사에서 이상 관측을 찾은 위치. 차량별 재판정 커서와 독립적이다. */
public final class TripQualityDiscovery {
    private Instant cursorAt;
    private long cursorBatchId;
    private final Instant until;
    private final long maximumGapSeconds;
    private boolean completed;

    public TripQualityDiscovery(final Instant cursorAt, final long cursorBatchId, final Instant until,
        final long maximumGapSeconds, final boolean completed) {
        this.cursorAt = cursorAt;
        this.cursorBatchId = cursorBatchId;
        this.until = until;
        this.maximumGapSeconds = maximumGapSeconds;
        this.completed = completed;
    }
    public void verifyResume(final Instant requestedUntil, final long requestedGapSeconds) {
        if (!Objects.equals(until, requestedUntil) || maximumGapSeconds != requestedGapSeconds) {
            throw new IllegalArgumentException("정리 작업의 종료 시각과 시간 기준을 변경할 수 없다");
        }
    }
    public void advance(final Instant lastAt, final long lastId, final int processed, final int limit) {
        if (completed) { throw new IllegalStateException("완료된 발견 작업은 진행할 수 없다"); }
        if (processed > 0) { cursorAt = lastAt; cursorBatchId = lastId; }
        completed = processed < limit;
    }
    public Instant cursorAt() { return cursorAt; }
    public long cursorBatchId() { return cursorBatchId; }
    public Instant until() { return until; }
    public long maximumGapSeconds() { return maximumGapSeconds; }
    public boolean completed() { return completed; }
}
