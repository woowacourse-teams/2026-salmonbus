package com.gustler.backend.api.station.domain;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 지금이 기점 첫차·막차 출발 시각의 어디쯤인지 나타낸다.
 *
 * <p>운행 종료 판정이 아니다. 시간표는 기점 출발 시각이라 막차 출발 뒤에도 그 차가 아직 오는 중일 수 있다.
 * 막차가 첫차보다 이르면 자정을 넘긴 막차로 보고 다음 날 시각으로 해석한다.
 */
public enum ScheduleWindow {

    BEFORE_FIRST_DEPARTURE, WITHIN_DEPARTURES, AFTER_LAST_DEPARTURE, UNKNOWN;

    public static ScheduleWindow of(String firstDepartureTime, String lastDepartureTime, LocalTime now) {
        LocalTime first = parse(firstDepartureTime);
        LocalTime last = parse(lastDepartureTime);
        if (first == null || last == null) {
            return UNKNOWN;
        }
        if (!last.isBefore(first)) {
            if (now.isBefore(first)) {
                return BEFORE_FIRST_DEPARTURE;
            }
            if (now.isAfter(last)) {
                return AFTER_LAST_DEPARTURE;
            }
            return WITHIN_DEPARTURES;
        }
        if (!now.isBefore(first) || !now.isAfter(last)) {
            return WITHIN_DEPARTURES;
        }
        return BEFORE_FIRST_DEPARTURE;
    }

    private static LocalTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }
}
