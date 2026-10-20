package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 캐시의 공개 동작 검증. 실제 DB·커밋 연결 검증을 대체하지 않는다. */
class RecentArrivalObservationCacheTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant AFTER = NOW.minusSeconds(1800);
    private final ArrivalObservationQuery query = mock(ArrivalObservationQuery.class);
    private final RecentArrivalObservationCache cache = cache(true, 2);

    @Test
    void 반복해서_평가하는_관측은_상세_정보를_다시_읽지_않는다() {
        // given
        ArrivalCandidate row = candidate(10, NOW.minusSeconds(15));
        givenRows(row);
        var first = cache.begin(NOW);
        read(first, 1, false);
        first.committed(NOW);

        // when
        var result = read(cache.begin(NOW.plusSeconds(10)), 1, false);

        // then
        assertThat(result).containsExactly(row);
        verify(query, times(1)).findByIds(1, "bus", List.of(10L));
        verify(query, times(2)).findIdsAfter(1, "bus", AFTER, 400);
    }

    @Test
    void 늦게_저장된_관측도_다음_정산에_반영한다() {
        // given
        ArrivalCandidate later = candidate(20, NOW.minusSeconds(15));
        givenRows(later);
        var first = cache.begin(NOW);
        read(first, 1, false);
        first.committed(NOW);
        ArrivalCandidate delayed = candidate(5, NOW.minusSeconds(30));
        when(query.findIdsAfter(1, "bus", AFTER, 400)).thenReturn(List.of(5L, 20L));
        when(query.findByIds(1, "bus", List.of(5L))).thenReturn(List.of(delayed));

        // when
        var result = read(cache.begin(NOW), 1, false);

        // then
        assertThat(result).containsExactly(delayed, later);
    }

    @Test
    void 같은_시각의_관측은_ID_순서대로_평가한다() {
        // given
        ArrivalCandidate first = candidate(10, NOW.minusSeconds(15));
        ArrivalCandidate second = candidate(20, first.observedAt());
        when(query.findIdsAfter(1, "bus", AFTER, 400)).thenReturn(List.of(10L, 20L));
        when(query.findByIds(1, "bus", List.of(10L, 20L))).thenReturn(List.of(second, first));

        // when
        var result = read(cache.begin(NOW), 1, false);

        // then
        assertThat(result).containsExactly(first, second);
    }

    @Test
    void 품질검사로_바뀐_관측은_DB에서_다시_읽는다() {
        // given
        ArrivalCandidate before = candidate(10, NOW.minusSeconds(15));
        givenRows(before);
        var first = cache.begin(NOW);
        read(first, 1, false);
        first.committed(NOW);
        ArrivalCandidate changed = new ArrivalCandidate(10, before.vehicle(), null, true);
        when(query.findByIds(1, "bus", List.of(10L))).thenReturn(List.of(changed));

        // when
        var result = read(cache.begin(NOW), 2, false);

        // then
        assertThat(result).containsExactly(changed);
        verify(query, times(2)).findByIds(1, "bus", List.of(10L));
    }

    @Test
    void 품질검사_중에는_캐시를_사용하지_않는다() {
        // given
        ArrivalCandidate row = candidate(10, NOW.minusSeconds(15));
        givenRows(row);
        var first = cache.begin(NOW);
        read(first, 1, false);
        first.committed(NOW);
        when(query.findAfter(1, "bus", AFTER, 400)).thenReturn(List.of());

        // when
        var result = read(cache.begin(NOW), 1, true);

        // then
        assertThat(result).isEmpty();
        verify(query).findAfter(1, "bus", AFTER, 400);
        verify(query, times(1)).findIdsAfter(1, "bus", AFTER, 400);
    }

    @Test
    void 보관_한도를_넘은_관측은_필요할_때_DB에서_읽는다() {
        // given
        ArrivalCandidate first = candidate(10, NOW.minusSeconds(30));
        ArrivalCandidate second = candidate(20, NOW.minusSeconds(20));
        ArrivalCandidate third = candidate(30, NOW.minusSeconds(10));
        when(query.findIdsAfter(1, "bus", AFTER, 400)).thenReturn(List.of(10L, 20L, 30L));
        when(query.findByIds(1, "bus", List.of(10L, 20L, 30L)))
            .thenReturn(List.of(first, second, third));
        var round = cache.begin(NOW);
        read(round, 1, false);
        round.committed(NOW);
        when(query.findIdsAfter(1, "bus", AFTER, 400)).thenReturn(List.of(10L));
        when(query.findByIds(1, "bus", List.of(10L))).thenReturn(List.of(first));

        // when
        var result = read(cache.begin(NOW), 1, false);

        // then
        assertThat(result).containsExactly(first);
        verify(query).findByIds(1, "bus", List.of(10L));
    }

    @Test
    void 여러_회차에서_읽어도_공유_캐시의_보관_한도를_넘지_않는다() {
        // given
        ArrivalCandidate first = candidate(10, NOW.minusSeconds(30));
        ArrivalCandidate second = candidate(20, NOW.minusSeconds(20));
        ArrivalCandidate third = candidate(30, NOW.minusSeconds(10));
        for (var candidate : List.of(first, second, third)) {
            givenRows(candidate);
            var round = cache.begin(NOW);
            read(round, 1, false);
            round.committed(NOW);
        }
        givenRows(first);

        // when
        var result = read(cache.begin(NOW), 1, false);

        // then
        assertThat(result).containsExactly(first);
        verify(query, times(2)).findByIds(1, "bus", List.of(10L));
    }

    @Test
    void 커밋을_기다리는_동안_보관_시간이_지나면_캐시에_넣지_않는다() {
        // given
        ArrivalCandidate row = candidate(10, NOW.minusSeconds(1799));
        givenRows(row);
        var first = cache.begin(NOW);
        read(first, 1, false);
        first.committed(NOW.plusSeconds(1));

        // when
        var result = read(cache.begin(NOW.plusSeconds(1)), 1, false);

        // then
        assertThat(result).containsExactly(row);
        verify(query, times(2)).findByIds(1, "bus", List.of(10L));
    }

    @Test
    void 보관_시간이_지난_관측은_캐시에_남기지_않는다() {
        // given
        ArrivalCandidate row = candidate(10, NOW.minusSeconds(1799));
        givenRows(row);
        var first = cache.begin(NOW);
        read(first, 1, false);
        first.committed(NOW);

        // when
        var result = read(cache.begin(NOW.plusSeconds(1)), 1, false);

        // then
        assertThat(result).containsExactly(row);
        verify(query, times(2)).findByIds(1, "bus", List.of(10L));
    }

    @Test
    void 커밋되지_않은_회차의_관측은_다음_회차에서_재사용하지_않는다() {
        // given
        givenRows(candidate(10, NOW.minusSeconds(15)));
        read(cache.begin(NOW), 1, false);

        // when
        read(cache.begin(NOW), 1, false);

        // then
        verify(query, times(2)).findByIds(1, "bus", List.of(10L));
    }

    @Test
    void 캐시가_비어도_DB에서_같은_관측을_읽는다() {
        // given
        ArrivalCandidate row = candidate(10, NOW.minusSeconds(15));
        givenRows(row);
        var first = cache.begin(NOW);
        read(first, 1, false);
        first.committed(NOW);
        var restarted = cache(true, 2);

        // when
        var result = read(restarted.begin(NOW), 1, false);

        // then
        assertThat(result).containsExactly(row);
        verify(query, times(2)).findByIds(1, "bus", List.of(10L));
    }

    @Test
    void 캐시를_끄면_기존_조회만_사용한다() {
        // given
        ArrivalCandidate row = candidate(10, NOW.minusSeconds(15));
        when(query.findAfter(1, "bus", AFTER, 400)).thenReturn(List.of(row));

        // when
        var result = read(cache(false, 2).begin(NOW), 1, false);

        // then
        assertThat(result).containsExactly(row);
        verify(query).findAfter(1, "bus", AFTER, 400);
        verifyNoMoreInteractions(query);
    }

    @Test
    void 상세_조회에서_관측이_사라지면_기존_조회로_다시_판단한다() {
        // given
        when(query.findIdsAfter(1, "bus", AFTER, 400)).thenReturn(List.of(10L));
        when(query.findByIds(1, "bus", List.of(10L))).thenReturn(List.of());
        when(query.findAfter(1, "bus", AFTER, 400)).thenReturn(List.of());

        // when
        var result = read(cache.begin(NOW), 1, false);

        // then
        assertThat(result).isEmpty();
        verify(query).findAfter(1, "bus", AFTER, 400);
    }

    @Test
    void 늦게_끝난_회차가_최신_품질검사_결과를_덮어쓰지_않는다() {
        // given
        ArrivalCandidate old = candidate(10, NOW.minusSeconds(15));
        givenRows(old);
        var previous = cache.begin(NOW);
        read(previous, 1, false);
        ArrivalCandidate changed = new ArrivalCandidate(10, old.vehicle(), null, true);
        when(query.findByIds(1, "bus", List.of(10L))).thenReturn(List.of(changed));
        var latest = cache.begin(NOW);
        read(latest, 2, false);
        latest.committed(NOW);
        previous.committed(NOW);

        // when
        var result = read(cache.begin(NOW), 2, false);

        // then
        assertThat(result).containsExactly(changed);
        verify(query, times(2)).findByIds(1, "bus", List.of(10L));
    }

    private List<ArrivalCandidate> read(RecentArrivalObservationCache.Round round,
        long revision, boolean investigating) {
        return round.read(query, 1, revision, investigating, "bus", AFTER, 400);
    }

    private void givenRows(ArrivalCandidate row) {
        when(query.findIdsAfter(1, "bus", AFTER, 400)).thenReturn(List.of(row.observationId()));
        when(query.findByIds(1, "bus", List.of(row.observationId()))).thenReturn(List.of(row));
    }

    private static RecentArrivalObservationCache cache(boolean enabled, int capacity) {
        return new RecentArrivalObservationCache(enabled, capacity, Duration.ofMinutes(30));
    }

    private static ArrivalCandidate candidate(long id, Instant at) {
        return new ArrivalCandidate(id, new ObservedVehicle("bus", 1, 5, at, 20, 1), 0L, true);
    }
}
