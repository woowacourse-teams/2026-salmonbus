package com.gustler.backend.api.push.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public record NotificationSchedule(
    LocalTime time,
    int weekdays
) {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern TIME_FORMAT = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
    private static final DateTimeFormatter CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> WEEKDAY_NAMES = List.of(
        "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"
    );

    public NotificationSchedule {
        if (time == null) {
            throw new IllegalArgumentException();
        }
        if (weekdays < 1 || weekdays > 127) {
            throw new IllegalArgumentException();
        }
    }

    public static NotificationSchedule parse(String time, List<String> rawWeekdays) {
        if (time == null || !TIME_FORMAT.matcher(time).matches()) {
            throw new IllegalArgumentException();
        }
        if (rawWeekdays == null || rawWeekdays.isEmpty()) {
            throw new IllegalArgumentException();
        }
        int bits = 0;
        Set<String> unique = new LinkedHashSet<>();
        for (String name : rawWeekdays) {
            bits |= bitOf(name);
            unique.add(name);
        }
        if (unique.size() != rawWeekdays.size()) {
            throw new IllegalArgumentException();
        }
        return new NotificationSchedule(LocalTime.parse(time), bits);
    }

    public Instant nextAfter(Instant after) {
        if (after == null) {
            throw new IllegalArgumentException();
        }
        ZonedDateTime seoul = after.atZone(SEOUL);
        LocalDate start = seoul.toLocalDate();
        for (int day = 0; day <= 7; day++) {
            LocalDate date = start.plusDays(day);
            if (!includes(date.getDayOfWeek())) {
                continue;
            }
            Instant candidate = ZonedDateTime.of(date, time, SEOUL).toInstant();
            if (candidate.isAfter(after)) {
                return candidate;
            }
        }
        throw new IllegalStateException();
    }

    public String timeText() {
        return formatTime(time);
    }

    public List<String> weekdayNames() {
        return namesOf(weekdays);
    }

    public static String formatTime(LocalTime value) {
        return CLOCK_TIME.format(value);
    }

    public static List<String> namesOf(int bits) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < WEEKDAY_NAMES.size(); index++) {
            if ((bits & (1 << index)) != 0) {
                names.add(WEEKDAY_NAMES.get(index));
            }
        }
        return List.copyOf(names);
    }

    private boolean includes(DayOfWeek day) {
        return (weekdays & (1 << (day.getValue() - 1))) != 0;
    }

    private static int bitOf(String name) {
        if (name == null) {
            throw new IllegalArgumentException();
        }
        int index = WEEKDAY_NAMES.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException();
        }
        return 1 << index;
    }
}
