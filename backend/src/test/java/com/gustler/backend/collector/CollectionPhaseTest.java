package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class CollectionPhaseTest {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Clock KOREAN_CLOCK = Clock.fixed(Instant.EPOCH, KOREA);

    private static final int PEAK_INTERVAL_SECONDS = 15;
    private static final int OFF_PEAK_INTERVAL_SECONDS = 20;
    private static final int DEEP_NIGHT_INTERVAL_SECONDS = 600;
    private static final int HOURS_PER_DAY = 24;

    @ParameterizedTest
    @ValueSource(ints = {0})
    void 자정부터_1시_전까지는_심야_꼬리다(
        final int hour
    ) {
        // when
        final CollectionPhase actual = CollectionPhase.atHour(hour);

        // then
        assertThat(actual).isEqualTo(CollectionPhase.NIGHT_TAIL);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void 새벽_1시부터_4시_전까지는_심야다(
        final int hour
    ) {
        // when
        final CollectionPhase actual = CollectionPhase.atHour(hour);

        // then
        assertThat(actual).isEqualTo(CollectionPhase.DEEP_NIGHT);
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6})
    void 새벽_4시부터_7시_전까지는_이른_아침이다(
        final int hour
    ) {
        // when
        final CollectionPhase actual = CollectionPhase.atHour(hour);

        // then
        assertThat(actual).isEqualTo(CollectionPhase.EARLY_MORNING);
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 8})
    void 아침_7시부터_9시_전까지는_아침_첨두다(
        final int hour
    ) {
        // when
        final CollectionPhase actual = CollectionPhase.atHour(hour);

        // then
        assertThat(actual).isEqualTo(CollectionPhase.MORNING_PEAK);
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 10, 11, 12, 13, 14, 15, 16})
    void 아침_9시부터_오후_5시_전까지는_낮이다(
        final int hour
    ) {
        // when
        final CollectionPhase actual = CollectionPhase.atHour(hour);

        // then
        assertThat(actual).isEqualTo(CollectionPhase.DAYTIME);
    }

    @ParameterizedTest
    @ValueSource(ints = {17, 18, 19})
    void 오후_5시부터_8시_전까지는_저녁_첨두다(
        final int hour
    ) {
        // when
        final CollectionPhase actual = CollectionPhase.atHour(hour);

        // then
        assertThat(actual).isEqualTo(CollectionPhase.EVENING_PEAK);
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 21, 22})
    void 밤_8시부터_11시_전까지는_늦은_저녁이다(
        final int hour
    ) {
        // when
        final CollectionPhase actual = CollectionPhase.atHour(hour);

        // then
        assertThat(actual).isEqualTo(CollectionPhase.LATE_EVENING);
    }

    @ParameterizedTest
    @ValueSource(ints = {23})
    void 밤_11시부터_자정까지는_심야_꼬리다(
        final int hour
    ) {
        // when
        final CollectionPhase actual = CollectionPhase.atHour(hour);

        // then
        assertThat(actual).isEqualTo(CollectionPhase.NIGHT_TAIL);
    }

    @Test
    void 하루_스물네_시간이_모두_어느_한_단계에_든다() {
        // when
        final long actual = IntStream.range(0, HOURS_PER_DAY)
            .mapToObj(CollectionPhase::atHour)
            .count();

        // then
        assertThat(actual).isEqualTo(HOURS_PER_DAY);
    }

    @ParameterizedTest
    @EnumSource(value = CollectionPhase.class, names = {"MORNING_PEAK", "EVENING_PEAK", "LATE_EVENING"})
    void 첨두와_늦은_저녁은_15초마다_부른다(
        CollectionPhase phase
    ) {
        // when
        final int actual = phase.intervalSeconds();

        // then
        assertThat(actual).isEqualTo(PEAK_INTERVAL_SECONDS);
    }

    @ParameterizedTest
    @EnumSource(value = CollectionPhase.class, names = {"EARLY_MORNING", "DAYTIME", "NIGHT_TAIL"})
    void 이른_아침과_낮과_심야_꼬리는_20초마다_부른다(
        CollectionPhase phase
    ) {
        // when
        final int actual = phase.intervalSeconds();

        // then
        assertThat(actual).isEqualTo(OFF_PEAK_INTERVAL_SECONDS);
    }

    @Test
    void 심야는_600초마다_부른다() {
        // when
        final int actual = CollectionPhase.DEEP_NIGHT.intervalSeconds();

        // then
        assertThat(actual).isEqualTo(DEEP_NIGHT_INTERVAL_SECONDS);
    }

    @Test
    void 세계_표준시가_아니라_한국_시각으로_단계를_고른다() {
        // given 세계 표준시로는 20시라 늦은 저녁이지만 한국 시각으로는 다음날 새벽 5시다
        final Instant utcEvening = Instant.parse("2026-08-28T20:00:00Z");

        // when
        final CollectionPhase actual = CollectionPhase.at(utcEvening, KOREAN_CLOCK);

        // then
        assertThat(actual).isEqualTo(CollectionPhase.EARLY_MORNING);
    }

    @Test
    void 단계마다_하루를_다_돌았을_때의_호출_수를_센다() {
        // when
        final int actual = CollectionPhase.DEEP_NIGHT.dailyCallsPerRoute();

        // then
        assertThat(actual).isEqualTo(18);
    }

    @Test
    void 자정을_넘는_심야_꼬리도_두_시간으로_센다() {
        // when
        final int actual = CollectionPhase.NIGHT_TAIL.dailyCallsPerRoute();

        // then
        assertThat(actual).isEqualTo(360);
    }

    @Test
    void 어느_단계도_같은_시각을_두_번_담지_않는다() {
        // when
        final long actual = IntStream.range(0, HOURS_PER_DAY)
            .mapToObj(hour -> Arrays.stream(CollectionPhase.values())
                .filter(phase -> phase == CollectionPhase.atHour(hour))
                .count())
            .filter(coveringPhases -> coveringPhases != 1)
            .count();

        // then
        assertThat(actual).isZero();
    }
}
