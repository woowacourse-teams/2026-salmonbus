package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomeCount;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomesStore;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import com.gustler.backend.forecasting.domain.model.SameDayFullOutcomes;
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
    private SameDayFullOutcomesStore repository;

    private SameDayFullOutcomesService service;

    @BeforeEach
    void 집계를_세운다() {
        service = new SameDayFullOutcomesService(repository);
    }

    @Test
    void 표에_오늘_행이_있으면_그_값을_평균으로_돌려준다() {
        // given
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of(TALLY));

        // when
        Map<Integer, SameDayFullOutcomes> actual = service.outcomesFor(ROUTE_3330, 1L, SETTLED_THROUGH.plusSeconds(60));

        // then
        assertThat(actual).containsExactly(Map.entry(STOPS_TO_TARGET, new SameDayFullOutcomes(2, 1, 0.31)));
        verify(repository, never()).countFromSource(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 집계가_없으면_원본을_조회하지_않고_당일_보정_없이_반환한다() {
        // given
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of());

        // when
        Map<Integer, SameDayFullOutcomes> actual = service.outcomesFor(ROUTE_3330, 1L, SETTLED_THROUGH.plusSeconds(60));

        // then
        assertThat(actual).isEmpty();
        verify(repository, never()).upsertCounts(anyLong(), anyLong(), any(), any());
        verify(repository, never()).countFromSource(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 예보_시각보다_미래인_성적은_원본_재조회_없이_보정에서_제외한다() {
        // given
        // 장애 뒤 처리가 밀린 batch다.
        Instant earlierBatch = SETTLED_THROUGH.minusSeconds(60);
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of(TALLY));

        // when
        Map<Integer, SameDayFullOutcomes> actual = service.outcomesFor(ROUTE_3330, 1L, earlierBatch);

        // then
        assertThat(actual).isEmpty();
        verify(repository, never()).upsertCounts(anyLong(), anyLong(), any(), any());
        verify(repository, never()).countFromSource(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 예보_시각이_반영된_도착과_같은_순간이면_표를_쓴다() {
        // given
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of(TALLY));

        // when
        Map<Integer, SameDayFullOutcomes> actual = service.outcomesFor(ROUTE_3330, 1L, SETTLED_THROUGH);

        // then
        assertThat(actual).containsKey(STOPS_TO_TARGET);
        verify(repository, never()).countFromSource(anyLong(), anyLong(), any(), eq(SETTLED_THROUGH));
    }

    @Test
    void 같은_노선과_도착일과_예보거리의_정산을_한번에_더한다() {
        // given
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of(TALLY));
        SettledForecast full = settledOn(ROUTE_3330, SETTLED_THROUGH, 0);
        SettledForecast notFull = settledOn(ROUTE_3330, SETTLED_THROUGH, 7);

        // when
        service.record(List.of(full, notFull));

        // then
        verify(repository).addCounts(ROUTE_3330, 1L, DAY, List.of(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 2, 1, 0.82, SETTLED_THROUGH)));
        verify(repository, never()).countFromSource(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 미초기화_날짜의_정산은_초기화하거나_부분_집계를_만들지_않는다() {
        // given
        // 배포 전에 닫힌 예보가 원본에만 있다
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of());

        // when
        service.record(List.of(settledOn(ROUTE_3330, SETTLED_THROUGH, 0)));

        // then
        verify(repository, never()).upsertCounts(anyLong(), anyLong(), any(), any());
        verify(repository, never()).countFromSource(anyLong(), anyLong(), any(), any());
        verify(repository, never()).addCounts(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 노선별_초기화_여부에_따라_준비된_집계에만_정산분을_더한다() {
        // given
        // 3330은 집계가 있고 1650은 없다.
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of(TALLY));
        when(repository.findCounts(ROUTE_1650, 1L, DAY)).thenReturn(List.of());
        SettledForecast on3330 = settledOn(ROUTE_3330, SETTLED_THROUGH, 0);
        SettledForecast on1650 = settledOn(ROUTE_1650, SETTLED_THROUGH, 7);

        // when
        service.record(List.of(on3330, on1650));

        // then
        verify(repository).addCounts(ROUTE_3330, 1L, DAY, List.of(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 1, 1, 0.41, SETTLED_THROUGH)));
        verify(repository, never()).upsertCounts(anyLong(), anyLong(), any(), any());
        verify(repository, never()).countFromSource(anyLong(), anyLong(), any(), any());
        verify(repository, never()).addCounts(eq(ROUTE_1650), anyLong(), any(), any());
    }

    @Test
    void 도착_날짜별_초기화_여부에_따라_준비된_집계에만_정산분을_더한다() {
        // given
        // 오늘 집계는 있고 어제 집계는 없다
        Instant yesterdayArrival = SETTLED_THROUGH.minus(Duration.ofDays(1));
        SeoulDay yesterday = SeoulDay.containing(yesterdayArrival);
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of(TALLY));
        when(repository.findCounts(ROUTE_3330, 1L, yesterday)).thenReturn(List.of());
        SettledForecast today = settledOn(ROUTE_3330, SETTLED_THROUGH, 0);
        SettledForecast lateSettled = settledOn(ROUTE_3330, yesterdayArrival, 0);

        // when
        service.record(List.of(today, lateSettled));

        // then
        verify(repository).addCounts(ROUTE_3330, 1L, DAY, List.of(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 1, 1, 0.41, SETTLED_THROUGH)));
        verify(repository, never()).upsertCounts(anyLong(), anyLong(), any(), any());
        verify(repository, never()).countFromSource(anyLong(), anyLong(), any(), any());
        verify(repository, never()).addCounts(eq(ROUTE_3330), anyLong(), eq(yesterday), any());
    }

    @Test
    void 도착_순서가_섞여도_가장_늦은_도착시각을_유지한다() {
        // when
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of(TALLY));
        service.record(List.of(settledOn(ROUTE_3330, SETTLED_THROUGH.plusSeconds(60), 0),
            settledOn(ROUTE_3330, SETTLED_THROUGH, 7)));

        // then
        verify(repository).addCounts(ROUTE_3330, 1L, DAY, List.of(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 2, 1, 0.82, SETTLED_THROUGH.plusSeconds(60))));
    }

    @Test
    void KST_자정_전후의_정산을_다른_날짜로_묶는다() {
        // when
        Instant before = Instant.parse("2026-08-19T14:59:59Z");
        Instant after = before.plusSeconds(1);
        SeoulDay nextDay = SeoulDay.containing(after);
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of(TALLY));
        when(repository.findCounts(ROUTE_3330, 1L, nextDay)).thenReturn(List.of(TALLY));
        service.record(List.of(settledOn(ROUTE_3330, before, 0), settledOn(ROUTE_3330, after, 7)));

        // then
        verify(repository).addCounts(ROUTE_3330, 1L, DAY, List.of(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 1, 1, 0.41, before)));
        verify(repository).addCounts(ROUTE_3330, 1L, nextDay, List.of(
            new SameDayFullOutcomeCount(STOPS_TO_TARGET, 1, 0, 0.41, after)));
    }

    @Test
    void 같은_노선과_날짜라도_모델이_다르면_정산을_분리한다() {
        // when
        when(repository.findCounts(ROUTE_3330, 1L, DAY)).thenReturn(List.of(TALLY));
        when(repository.findCounts(ROUTE_3330, 2L, DAY)).thenReturn(List.of(TALLY));
        service.record(List.of(new SettledForecast(ROUTE_3330, 1L, 3, .8, SETTLED_THROUGH, 0),
            new SettledForecast(ROUTE_3330, 2L, 3, .2, SETTLED_THROUGH, 8)));

        // then
        verify(repository).addCounts(ROUTE_3330, 1L, DAY,
            List.of(new SameDayFullOutcomeCount(3, 1, 1, .8, SETTLED_THROUGH)));
        verify(repository).addCounts(ROUTE_3330, 2L, DAY,
            List.of(new SameDayFullOutcomeCount(3, 1, 0, .2, SETTLED_THROUGH)));
    }

    @Test
    void 새_모델의_집계가_없으면_이전_모델로_대체하지_않는다() {
        // when
        when(repository.findCounts(ROUTE_3330, 2L, DAY)).thenReturn(List.of());

        // then
        assertThat(service.outcomesFor(ROUTE_3330, 2L, SETTLED_THROUGH)).isEmpty();
        verify(repository, never()).findCounts(ROUTE_3330, 1L, DAY);
    }

    private static SettledForecast settledOn(
        final long routeId,
        Instant arrivedAt,
        final int seatsOnArrival
    ) {
        return new SettledForecast(routeId, 1L, STOPS_TO_TARGET, 0.41, arrivedAt, seatsOnArrival);
    }
}
