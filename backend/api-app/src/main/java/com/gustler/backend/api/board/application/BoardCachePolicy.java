package com.gustler.backend.api.board.application;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관측 시각의 시간대별 수집 간격에 맞춰 탑승판의 클라이언트 캐시 수명을 정한다.
 */
@Component
@RequiredArgsConstructor
public class BoardCachePolicy {
    private static final Duration DENSE_HOUR_MAX_AGE = Duration.ofSeconds(15);
    private static final Duration REGULAR_HOUR_MAX_AGE = Duration.ofSeconds(20);
    private static final Duration OVERNIGHT_MAX_AGE = Duration.ofSeconds(600);

    private final Clock clock;

    /**
     * 관측 시각을 주입된 시계의 시간대로 해석해 혼잡·심야·일반 시간대별 유효기간을 고른다.
     */
    public Duration maxAgeAt(
        OffsetDateTime observedAt
    ) {
        final int hour = observedAt.atZoneSameInstant(clock.getZone()).getHour();
        if (isDenseHour(hour)) {
            return DENSE_HOUR_MAX_AGE;
        }
        if (hour >= 1 && hour < 4) {
            return OVERNIGHT_MAX_AGE;
        }
        return REGULAR_HOUR_MAX_AGE;
    }

    private boolean isDenseHour(
        final int hour
    ) {
        return hour >= 7 && hour < 9
            || hour >= 17 && hour < 23;
    }
}
