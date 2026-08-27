package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Instant;

public enum TimeSlot {

    MORNING,
    EVENING,
    OTHER,
    ;

    private static final int MORNING_FROM_HOUR = 7;
    private static final int MORNING_UNTIL_HOUR = 9;
    private static final int EVENING_FROM_HOUR = 17;
    private static final int EVENING_UNTIL_HOUR = 20;

    public static TimeSlot of(
        final Instant forecastedAt,
        final Clock clock
    ) {
        final int hour = forecastedAt.atZone(clock.getZone()).getHour();
        if (isMorning(hour)) {
            return MORNING;
        }
        if (isEvening(hour)) {
            return EVENING;
        }
        return OTHER;
    }

    private static boolean isMorning(
        final int hour
    ) {
        return MORNING_FROM_HOUR <= hour && hour < MORNING_UNTIL_HOUR;
    }

    private static boolean isEvening(
        final int hour
    ) {
        return EVENING_FROM_HOUR <= hour && hour < EVENING_UNTIL_HOUR;
    }
}
