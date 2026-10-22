package com.gustler.backend.api.observation;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 관측 시각의 한국 시각대를 기준으로 관측 만료 시각을 계산한다.
 * 조회 시각으로 관측의 유효 기간을 다시 늘리지 않는다.
 */
public final class ObservationFreshness {
    public static final Duration MAX_WINDOW = Duration.ofMinutes(10);
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private ObservationFreshness() {
    }

    public static OffsetDateTime staleAt(OffsetDateTime observedAt) {
        final int hour = observedAt.atZoneSameInstant(KOREA).getHour();
        return observedAt.plus(windowFor(hour));
    }

    private static Duration windowFor(final int hour) {
        if (hour >= 1 && hour < 4) {
            return MAX_WINDOW;
        }
        return DEFAULT_WINDOW;
    }
}
