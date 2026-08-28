package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CollectionScheduleTest {

    private static final int DAILY_LIMIT_10000 = 10_000;
    private static final int DAILY_CALLS_PER_ROUTE = 4_278;

    @Test
    void 한_노선을_하루_다_돌면_4278번_부른다() {
        // when
        final int actual = CollectionSchedule.dailyCallsPerRoute();

        // then
        assertThat(actual).isEqualTo(DAILY_CALLS_PER_ROUTE);
    }

    @Test
    void 두_노선을_하루_다_돌면_8556번_부른다() {
        // when
        final int actual = CollectionSchedule.dailyCallsFor(2);

        // then
        assertThat(actual).isEqualTo(8_556);
    }

    @Test
    void 두_노선은_하루_한도_10000번_안에_들어온다() {
        // when
        final boolean actual = CollectionSchedule.fitsDailyLimit(2, DAILY_LIMIT_10000);

        // then
        assertThat(actual).isTrue();
    }

    @Test
    void 세_노선은_하루_한도_10000번을_넘는다() {
        // when
        final boolean actual = CollectionSchedule.fitsDailyLimit(3, DAILY_LIMIT_10000);

        // then
        assertThat(actual).isFalse();
    }
}
