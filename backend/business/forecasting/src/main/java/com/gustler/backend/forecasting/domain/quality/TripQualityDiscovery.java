package com.gustler.backend.forecasting.domain.quality;

import java.time.Instant;
import java.util.Objects;

/** 수동 조사에서 이상 관측을 찾은 위치. 차량별 재판정 커서와 독립적이다. */
public final class TripQualityDiscovery {
    private final Instant until;
    private final long maximumGapSeconds;
    private Instant cursorAt;
    private long cursorBatchId;
    private boolean completed;

    public TripQualityDiscovery(
        Instant cursorAt,
        final long cursorBatchId,
        Instant until,
        final long maximumGapSeconds,
        final boolean completed
    ) {
        this.cursorAt = cursorAt;
        this.cursorBatchId = cursorBatchId;
        this.until = until;
        this.maximumGapSeconds = maximumGapSeconds;
        this.completed = completed;
    }

    public void verifyResume(Instant requestedUntil, final long requestedGapSeconds) {
        if (!Objects.equals(until, requestedUntil) || maximumGapSeconds != requestedGapSeconds) {
            throw new IllegalArgumentException("정리 작업의 종료 시각과 시간 기준을 변경할 수 없다");
        }
    }

    /**
     * 처리 건수만큼 발견 커서를 전진시킨다. 짧은 페이지이면 완료하며 완료한 작업의 재진행은 예외로 거부한다.
     */
    public void advance(
        Instant lastAt,
        final long lastId,
        final int processed,
        final int limit
    ) {
        if (completed) {
            throw new IllegalStateException("완료된 발견 작업은 진행할 수 없다");
        }
        if (processed > 0) {
            cursorAt = lastAt;
            cursorBatchId = lastId;
        }
        completed = processed < limit;
    }

    public Instant cursorAt() {
        return cursorAt;
    }

    public long cursorBatchId() {
        return cursorBatchId;
    }

    public Instant until() {
        return until;
    }

    public long maximumGapSeconds() {
        return maximumGapSeconds;
    }

    public boolean completed() {
        return completed;
    }
}
