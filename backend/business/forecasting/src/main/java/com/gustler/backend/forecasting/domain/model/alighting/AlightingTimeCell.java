package com.gustler.backend.forecasting.domain.model.alighting;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

/** 서울 시각의 예보 발행 및 통과 정산 구간. 호출자가 해당 관측 시각을 선택한다. */
public enum AlightingTimeCell {
    WORK_MORNING("work-morning", "morning", 360),
    WORK_DAY("work-day", "", 0),
    WORK_EVENING("work-evening", "evening", 1020),
    WORK_NIGHT("work-night", "", 0),
    WEEKEND_DAY("weekend-day", "", 0),
    WEEKEND_NIGHT("weekend-night", "", 0),
    HOLIDAY_DAY("holiday-day", "", 0),
    HOLIDAY_NIGHT("holiday-night", "", 0);

    private final String key;
    private final String rushBand;
    private final int binStart;

    AlightingTimeCell(String key, String rushBand, int binStart) {
        this.key = key;
        this.rushBand = rushBand;
        this.binStart = binStart;
    }

    public static AlightingTimeCell of(ZonedDateTime seoulTime, boolean holiday) {
        int minute = minuteOf(seoulTime);
        boolean daytime = minute >= WORK_MORNING.binStart && minute < 1260;
        if (holiday) {
            return daytime ? HOLIDAY_DAY : HOLIDAY_NIGHT;
        }
        if (seoulTime.getDayOfWeek().getValue() >= 6) {
            return daytime ? WEEKEND_DAY : WEEKEND_NIGHT;
        }
        if (minute >= WORK_MORNING.binStart && minute < 600) {
            return WORK_MORNING;
        }
        if (minute >= WORK_EVENING.binStart && minute < 1260) {
            return WORK_EVENING;
        }
        return daytime ? WORK_DAY : WORK_NIGHT;
    }

    public static int minuteOf(ZonedDateTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    public String key() {
        return key;
    }

    public String rushBand() {
        return rushBand;
    }

    public boolean isRush() {
        return !rushBand.isEmpty();
    }

    public int settlementBin(ZonedDateTime seoulTime) {
        return (minuteOf(seoulTime) - binStart) / (isRush() ? 30 : 180);
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(AlightingTimeCell::key).toList();
    }

    public static List<String> extendedKeys() {
        return Arrays.stream(values()).filter(cell -> !cell.isRush()).map(AlightingTimeCell::key).toList();
    }
}
