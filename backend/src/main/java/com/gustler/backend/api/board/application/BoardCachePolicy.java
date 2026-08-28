package com.gustler.backend.api.board.application;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BoardCachePolicy {

    private static final Duration DENSE_HOUR_MAX_AGE = Duration.ofSeconds(15);
    private static final Duration REGULAR_HOUR_MAX_AGE = Duration.ofSeconds(20);
    private static final Duration OVERNIGHT_MAX_AGE = Duration.ofSeconds(600);

    private final Clock clock;

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
