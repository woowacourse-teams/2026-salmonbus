package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Instant;

public enum TimeBand {

    MORNING,
    EVENING,
    OTHER;

    private static final int MORNING_FROM_HOUR = 7;
    private static final int MORNING_UNTIL_HOUR = 9;
    private static final int EVENING_FROM_HOUR = 17;
    private static final int EVENING_UNTIL_HOUR = 20;

    public static TimeBand of(
        final Instant at,
        final Clock clock
    ) {
        final int hour = at.atZone(clock.getZone()).getHour();
        if (MORNING_FROM_HOUR <= hour && hour < MORNING_UNTIL_HOUR) {
            return MORNING;
        }
        if (EVENING_FROM_HOUR <= hour && hour < EVENING_UNTIL_HOUR) {
            return EVENING;
        }
        return OTHER;
    }
}
