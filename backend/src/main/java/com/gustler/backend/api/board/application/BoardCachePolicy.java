package com.gustler.backend.api.board.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class BoardCachePolicy {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration DENSE = Duration.ofSeconds(15);
    private static final Duration REGULAR = Duration.ofSeconds(20);
    private static final Duration OVERNIGHT = Duration.ofSeconds(600);

    public Duration maxAgeAt(final OffsetDateTime observedAt) {
        final int hour = observedAt.atZoneSameInstant(SEOUL).getHour();
        if (isDenseHour(hour)) {
            return DENSE;
        }
        if (hour >= 1 && hour < 4) {
            return OVERNIGHT;
        }
        return REGULAR;
    }

    private boolean isDenseHour(final int hour) {
        return hour >= 7 && hour < 9
            || hour >= 17 && hour < 23;
    }
}
