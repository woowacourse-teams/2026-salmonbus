package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TimeBandTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, SEOUL);
    private static final LocalDate ANY_DAY = LocalDate.of(2026, 8, 25);

    @ParameterizedTest
    @ValueSource(ints = {7, 8})
    void 오전_7시부터_8시까지는_아침이다(
        final int hour
    ) {
        // when
        final TimeBand actual = TimeBand.of(seoulTimeAt(hour), CLOCK);

        // then
        assertThat(actual).isEqualTo(TimeBand.MORNING);
    }

    @ParameterizedTest
    @ValueSource(ints = {17, 18, 19})
    void 오후_5시부터_7시까지는_저녁이다(
        final int hour
    ) {
        // when
        final TimeBand actual = TimeBand.of(seoulTimeAt(hour), CLOCK);

        // then
        assertThat(actual).isEqualTo(TimeBand.EVENING);
    }

    @ParameterizedTest
    @MethodSource("아침도_저녁도_아닌_시각")
    void 아침과_저녁_밖의_시각은_모두_기타다(
        final int hour
    ) {
        // when
        final TimeBand actual = TimeBand.of(seoulTimeAt(hour), CLOCK);

        // then
        assertThat(actual).isEqualTo(TimeBand.OTHER);
    }

    private static IntStream 아침도_저녁도_아닌_시각() {
        return IntStream.rangeClosed(0, 23)
            .filter(hour -> hour < 7 || (9 <= hour && hour < 17) || 20 <= hour);
    }

    private static Instant seoulTimeAt(
        final int hour
    ) {
        return ANY_DAY.atTime(LocalTime.of(hour, 30)).atZone(SEOUL).toInstant();
    }
}
