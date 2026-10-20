package com.gustler.backend.forecasting.domain.statistics;

import java.time.Clock;
import java.time.Instant;

/**
 * 관측 기준 시각을 통계·모델 입력의 시간대로 분류한다.
 * 조회 시각으로 과거 관측의 시간대를 바꾸지 않는다.
 */
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
        Instant forecastedAt,
        Clock clock
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
