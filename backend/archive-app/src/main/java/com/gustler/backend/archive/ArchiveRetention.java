package com.gustler.backend.archive;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 한국 날짜로 오늘·어제를 보존한다. 고정 48시간으로 계산하지 않는다. */
public final class ArchiveRetention {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private ArchiveRetention() {
    }

    public static Instant cutoff(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE)).minusDays(1).atStartOfDay(ZONE).toInstant();
    }

    public static Instant start(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }
}
