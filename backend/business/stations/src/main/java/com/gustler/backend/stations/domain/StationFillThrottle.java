package com.gustler.backend.stations.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 채우기 배치의 호출 간격·하루 사용량·멈춤을 판단한다. 단일 스레드에서만 쓴다.
 *
 * <p>순간 약 50회를 넘기면 원천이 약 60초 막는다(2026-11-05 실측). 차단 신호를 받으면 처음 65초,
 * 연속이면 두 배씩 최대 10분까지 쉰다. 하루 사용량은 한국 날짜로 센다.
 */
public final class StationFillThrottle {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final Duration blockWait;
    private final Duration blockWaitMaximum;
    private final int dailyLimit;
    private final int remainingFloor;

    private Instant pausedUntil = Instant.MIN;

    private String pauseReason;

    private int consecutiveBlocks;

    private LocalDate countedDate;

    private int callsToday;

    public StationFillThrottle(
        Duration blockWait,
        Duration blockWaitMaximum,
        final int dailyLimit,
        final int remainingFloor
    ) {
        this.blockWait = blockWait;
        this.blockWaitMaximum = blockWaitMaximum;
        this.dailyLimit = dailyLimit;
        this.remainingFloor = remainingFloor;
    }

    /**
     * 지금 원천을 불러도 되는지. 하루 상한을 넘기면 다음 날 0시까지 멈춘다.
     */
    public boolean mayCall(Instant now) {
        rollDate(now);
        if (now.isBefore(pausedUntil)) {
            return false;
        }
        if (callsToday >= dailyLimit) {
            pauseUntilTomorrow(now, "DAILY_LIMIT");
            return false;
        }
        return true;
    }

    public void called(Instant now) {
        rollDate(now);
        callsToday++;
    }

    /**
     * 정상 응답의 남은 값이 하한 아래면 그날 멈춘다.
     */
    public void observedRemaining(Integer remaining, Instant now) {
        consecutiveBlocks = 0;
        if (remaining != null && remaining < remainingFloor) {
            pauseUntilTomorrow(now, "REMAINING_LOW");
        }
    }

    public void blocked(Instant now) {
        consecutiveBlocks++;
        Duration wait = blockWait.multipliedBy(1L << Math.min(consecutiveBlocks - 1, 10));
        if (wait.compareTo(blockWaitMaximum) > 0) {
            wait = blockWaitMaximum;
        }
        pausedUntil = now.plus(wait);
        pauseReason = "THROTTLED";
    }

    public void exhausted(Instant now, String reason) {
        pauseUntilTomorrow(now, reason);
    }

    public String pauseReason() {
        return pauseReason;
    }

    public Instant pausedUntil() {
        return pausedUntil;
    }

    public int callsToday() {
        return callsToday;
    }

    private void pauseUntilTomorrow(Instant now, String reason) {
        pausedUntil = now.atZone(KOREA).toLocalDate().plusDays(1).atStartOfDay(KOREA).toInstant();
        pauseReason = reason;
    }

    private void rollDate(Instant now) {
        LocalDate today = now.atZone(KOREA).toLocalDate();
        if (!today.equals(countedDate)) {
            countedDate = today;
            callsToday = 0;
        }
    }
}
