package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.processor.seatdistribution.SameDayFullOutcomes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SameDayFullOutcomesServiceTest {

    private static final long ROUTE_3330 = 1L;
    private static final long ROUTE_1650 = 2L;
    private static final int STOPS_TO_TARGET = 3;

    /** 한국 시각 8월 19일 11시 20분에 도착이 확인됐다. */
    private static final Instant SETTLED_THROUGH = Instant.parse("2026-08-19T02:20:31Z");
    private static final SeoulDay DAY = SeoulDay.containing(SETTLED_THROUGH);
    private static final SameDayFullOutcomeCount TALLY =
        new SameDayFullOutcomeCount(STOPS_TO_TARGET, 2, 1, 0.62, SETTLED_THROUGH);

    @Mock
    private SameDayFullOutcomesRepository repository;

    private SameDayFullOutcomesService service;

    @BeforeEach
    void 집계를_세운다() {
        service = new SameDayFullOutcomesService(repository);
    }

    @Test
    void 표에_오늘_행이_있으면_그_값을_평균으로_돌려준다() {
        // given
        when(repository.findCounts(ROUTE_3330, DAY)).thenReturn(List.of(TALLY));

        // when
        Map<Integer, SameDayFullOutcomes> actual = service.outcomesFor(ROUTE_3330, SETTLED_THROUGH.plusSeconds(60));

        // then
        assertThat(actual).containsExactly(Map.entry(STOPS_TO_TARGET, new SameDayFullOutcomes(2, 1, 0.31)));
        verify(repository, never()).countFromSource(anyLong(), any(), any());
    }

    @Test
    void 표가_비어_있으면_원본에서_하루치를_세서_표에_넣고_그_값을_쓴다() {
        // given
        when(repository.findCounts(ROUTE_3330, DAY)).thenReturn(List.of());
        when(repository.countFromSource(ROUTE_3330, DAY, DAY.end())).thenReturn(List.of(TALLY));

        // when
        Map<Integer, SameDayFullOutcomes> actual = service.outcomesFor(ROUTE_3330, SETTLED_THROUGH.plusSeconds(60));

        // then
        assertThat(actual).containsKey(STOPS_TO_TARGET);
        verify(repository).upsertCounts(ROUTE_3330, DAY, List.of(TALLY));
    }

    @Test
    void 원본에도_없으면_빈_집계를_기록하고_예측값은_반환하지_않는다() {
        // given
        when(repository.findCounts(ROUTE_3330, DAY)).thenReturn(List.of());
        when(repository.countFromSource(ROUTE_3330, DAY, DAY.end())).thenReturn(List.of());

        // when
        Map<Integer, SameDayFullOutcomes> actual = service.outcomesFor(ROUTE_3330, SETTLED_THROUGH.plusSeconds(60));

        // then
        assertThat(actual).isEmpty();
        verify(repository).upsertCounts(ROUTE_3330, DAY, List.of(new SameDayFullOutcomeCount(0, 0, 0, 0, DAY.start())));
    }

    @Test
    void 예보_시각이_표에_반영된_도착보다_앞이면_표를_두고_원본에서_그_시각_기준으로_센다() {
        // given 장애 뒤 밀린 batch 다
        Instant earlierBatch = SETTLED_THROUGH.minusSeconds(60);
        when(repository.findCounts(ROUTE_3330, DAY)).thenReturn(List.of(TALLY));
        when(repository.countFromSource(ROUTE_3330, DAY, earlierBatch)).thenReturn(List.of());

        // when
        Map<Integer, SameDayFullOutcomes> actual = service.outcomesFor(ROUTE_3330, earlierBatch);

        // then
        assertThat(actual).isEmpty();
        verify(repository, never()).upsertCounts(anyLong(), any(), any());
    }

    @Test
    void 예보_시각이_반영된_도착과_같은_순간이면_표를_쓴다() {
        // given
        when(repository.findCounts(ROUTE_3330, DAY)).thenReturn(List.of(TALLY));

        // when
        Map<Integer, SameDayFullOutcomes> actual = service.outcomesFor(ROUTE_3330, SETTLED_THROUGH);

        // then
        assertThat(actual).containsKey(STOPS_TO_TARGET);
        verify(repository, never()).countFromSource(anyLong(), any(), eq(SETTLED_THROUGH));
    }

    @Test
    void 집계가_있으면_정산된_예보를_하나씩_더한다() {
        // given
        when(repository.findCounts(ROUTE_3330, DAY)).thenReturn(List.of(TALLY));
        SettledForecast full = settledOn(ROUTE_3330, SETTLED_THROUGH, 0);
        SettledForecast notFull = settledOn(ROUTE_3330, SETTLED_THROUGH, 7);

        // when
        service.record(List.of(full, notFull));

        // then
        verify(repository).add(full);
        verify(repository).add(notFull);
        verify(repository, never()).countFromSource(anyLong(), any(), any());
    }

    @Test
    void 집계가_비어_있으면_원본에서_하루치를_세서_넣고_정산분은_따로_더하지_않는다() {
        // given 배포 전에 닫힌 예보가 원본에만 있다
        when(repository.findCounts(ROUTE_3330, DAY)).thenReturn(List.of());
        when(repository.countFromSource(ROUTE_3330, DAY, DAY.end())).thenReturn(List.of(TALLY));

        // when
        service.record(List.of(settledOn(ROUTE_3330, SETTLED_THROUGH, 0)));

        // then
        verify(repository).upsertCounts(ROUTE_3330, DAY, List.of(TALLY));
        verify(repository, never()).add(any());
    }

    @Test
    void 노선이_다른_정산분은_노선마다_따로_판단한다() {
        // given 3330 은 집계가 있고 1650 은 없다
        when(repository.findCounts(ROUTE_3330, DAY)).thenReturn(List.of(TALLY));
        when(repository.findCounts(ROUTE_1650, DAY)).thenReturn(List.of());
        when(repository.countFromSource(ROUTE_1650, DAY, DAY.end())).thenReturn(List.of(TALLY));
        SettledForecast on3330 = settledOn(ROUTE_3330, SETTLED_THROUGH, 0);
        SettledForecast on1650 = settledOn(ROUTE_1650, SETTLED_THROUGH, 7);

        // when
        service.record(List.of(on3330, on1650));

        // then
        verify(repository).add(on3330);
        verify(repository).upsertCounts(ROUTE_1650, DAY, List.of(TALLY));
        verify(repository, never()).add(on1650);
    }

    @Test
    void 도착_날짜가_다른_정산분은_날짜마다_따로_판단한다() {
        // given 오늘 집계는 있고 어제 집계는 없다
        Instant yesterdayArrival = SETTLED_THROUGH.minus(Duration.ofDays(1));
        SeoulDay yesterday = SeoulDay.containing(yesterdayArrival);
        when(repository.findCounts(ROUTE_3330, DAY)).thenReturn(List.of(TALLY));
        when(repository.findCounts(ROUTE_3330, yesterday)).thenReturn(List.of());
        when(repository.countFromSource(ROUTE_3330, yesterday, yesterday.end())).thenReturn(List.of(TALLY));
        SettledForecast today = settledOn(ROUTE_3330, SETTLED_THROUGH, 0);
        SettledForecast lateSettled = settledOn(ROUTE_3330, yesterdayArrival, 0);

        // when
        service.record(List.of(today, lateSettled));

        // then
        verify(repository).add(today);
        verify(repository).upsertCounts(ROUTE_3330, yesterday, List.of(TALLY));
        verify(repository, never()).add(lateSettled);
    }

    private static SettledForecast settledOn(
        final long routeId,
        Instant arrivedAt,
        final int seatsOnArrival
    ) {
        return new SettledForecast(routeId, STOPS_TO_TARGET, 0.41, arrivedAt, seatsOnArrival);
    }
}
