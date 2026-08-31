package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * 예보의 시간대를 무엇으로 정하는지 본다.
 *
 * <p>몇 시가 아침인지는 {@link TimeSlotTest} 가 본다. 여기서 보는 것은 <b>어느 시각을 보느냐</b>다.
 */
class ForecastTimeSlotTest {

    private static final Clock KOREAN_CLOCK =
        Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private static final long ANY_BATCH_ID = 7L;
    private static final long ROUTE_VERSION_3330 = 1L;

    @Test
    void 시간대는_관측을_받은_시각으로_정해진다() {
        // given 아침 8시 30분에 받은 관측
        PendingForecastBatch batch = batchReceivedAt(Instant.parse("2026-08-19T08:30:00+09:00"));

        // when
        TimeSlot actual = ForecastTimeSlot.of(batch, KOREAN_CLOCK);

        // then
        assertThat(actual).isEqualTo(TimeSlot.MORNING);
    }

    @Test
    void 밀린_batch_를_한참_뒤에_돌려도_같은_시간대가_나온다() {
        // given 아침에 받았는데 예보는 낮에 돌린다
        PendingForecastBatch batch = batchReceivedAt(Instant.parse("2026-08-19T08:30:00+09:00"));
        Clock middayClock = Clock.fixed(Instant.parse("2026-08-19T13:00:00+09:00"), ZoneId.of("Asia/Seoul"));

        // when
        TimeSlot actual = ForecastTimeSlot.of(batch, middayClock);

        // then 언제 돌렸느냐로 아침이 그 밖이 되지 않는다
        assertThat(actual).isEqualTo(TimeSlot.MORNING);
    }

    @Test
    void 아침이_끝나는_9시에_받은_관측은_아침이_아니다() {
        // given
        PendingForecastBatch batch = batchReceivedAt(Instant.parse("2026-08-19T09:00:00+09:00"));

        // when
        TimeSlot actual = ForecastTimeSlot.of(batch, KOREAN_CLOCK);

        // then
        assertThat(actual).isEqualTo(TimeSlot.OTHER);
    }

    private static PendingForecastBatch batchReceivedAt(
        Instant responseReceivedAt
    ) {
        return new PendingForecastBatch(ANY_BATCH_ID, ROUTE_VERSION_3330, responseReceivedAt);
    }
}
