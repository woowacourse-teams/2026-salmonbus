package com.gustler.backend.api.push.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationScheduleTest {
    private static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI");
    private static final Instant BEFORE_SLOT = Instant.parse("2026-11-01T22:39:00Z");

    @Test
    void 평일_07시40분_저장은_같은_날_차례다() {
        NotificationSchedule schedule = NotificationSchedule.parse("07:40", WEEKDAYS);
        assertThat(schedule.nextAfter(BEFORE_SLOT)).isEqualTo(Instant.parse("2026-11-01T22:40:00Z"));
    }

    @Test
    void 같은_날_더_늦은_시각으로_바꾸면_그날_차례다() {
        NotificationSchedule schedule = NotificationSchedule.parse("08:00", WEEKDAYS);
        assertThat(schedule.nextAfter(Instant.parse("2026-11-01T22:50:00Z")))
            .isEqualTo(Instant.parse("2026-11-01T23:00:00Z"));
    }

    @Test
    void 저장_순간이_후보와_같으면_다음_평일이다() {
        NotificationSchedule schedule = NotificationSchedule.parse("07:40", WEEKDAYS);
        assertThat(schedule.nextAfter(Instant.parse("2026-11-01T22:40:00Z")))
            .isEqualTo(Instant.parse("2026-11-02T22:40:00Z"));
    }

    @Test
    void 월요일만_고르고_정각에_저장하면_다음_주다() {
        NotificationSchedule schedule = NotificationSchedule.parse("07:40", List.of("MON"));
        assertThat(schedule.nextAfter(Instant.parse("2026-11-01T22:40:00Z")))
            .isEqualTo(Instant.parse("2026-11-08T22:40:00Z"));
    }

    @Test
    void 요일_순서는_저장과_상관없이_월요일부터다() {
        NotificationSchedule schedule = NotificationSchedule.parse("07:30", List.of("TUE", "MON"));
        assertThat(schedule.weekdays()).isEqualTo(3);
        assertThat(schedule.weekdayNames()).containsExactly("MON", "TUE");
        assertThat(schedule.timeText()).isEqualTo("07:30");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"7:40", "07:40:00", "24:00", "07:60"})
    void 시각이_HHMM이_아니면_거절한다(String time) {
        assertThatThrownBy(() -> NotificationSchedule.parse(time, WEEKDAYS))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 요일이_없으면_거절한다() {
        assertThatThrownBy(() -> NotificationSchedule.parse("07:40", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationSchedule.parse("07:40", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 모르는_요일과_중복_요일은_거절한다() {
        assertThatThrownBy(() -> NotificationSchedule.parse("07:40", List.of("MONDAY")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationSchedule.parse("07:40", List.of("MON", "MON")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationSchedule.parse("07:40", java.util.Arrays.asList("MON", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
