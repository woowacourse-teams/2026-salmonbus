package com.gustler.backend.api.station.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScheduleWindowTest {
    @ParameterizedTest
    @CsvSource({
        "04:59, BEFORE_FIRST_DEPARTURE", "05:00, WITHIN_DEPARTURES", "22:30, WITHIN_DEPARTURES",
        "22:31, AFTER_LAST_DEPARTURE", "02:00, BEFORE_FIRST_DEPARTURE"
    })
    void 같은_날_막차는_첫차_전과_출발_시간_안과_막차_뒤로_나눈다(String now, ScheduleWindow expected) {
        // when
        ScheduleWindow window = ScheduleWindow.of("05:00", "22:30", LocalTime.parse(now));

        // then
        assertThat(window).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "23:50, WITHIN_DEPARTURES", "00:10, WITHIN_DEPARTURES", "00:20, WITHIN_DEPARTURES",
        "00:30, BEFORE_FIRST_DEPARTURE", "05:29, BEFORE_FIRST_DEPARTURE", "05:30, WITHIN_DEPARTURES"
    })
    void 막차가_첫차보다_이르면_자정을_넘긴_막차로_본다(String now, ScheduleWindow expected) {
        // when
        ScheduleWindow window = ScheduleWindow.of("05:30", "00:20", LocalTime.parse(now));

        // then
        assertThat(window).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL, 22:30", "05:00, NULL", "'', 22:30", "5시, 22:30"}, nullValues = "NULL")
    void 시각이_없거나_읽을_수_없으면_UNKNOWN이다(String first, String last) {
        // when
        ScheduleWindow window = ScheduleWindow.of(first, last, LocalTime.NOON);

        // then
        assertThat(window).isEqualTo(ScheduleWindow.UNKNOWN);
    }
}
