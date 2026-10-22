package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryService.Round;
import com.gustler.backend.forecasting.application.evaluation.RecentObservationBatches.Header;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Row;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationDeliveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private final RecentObservationBatches source = mock(RecentObservationBatches.class);
    private final ArrivalObservationQuery fallback = mock(ArrivalObservationQuery.class);
    private final ObservationDeliveryService memory = memory(10);

    @Test
    void 커밋된_수집_원본이_있으면_관측_본문을_다시_조회하지_않는다() {
        // given
        memory.receive(1, batch(1, 1, 10));
        headers(header(1, 1));

        // when
        Round round = memory.begin(NOW);
        assertThat(read(round, 1)).extracting(ArrivalCandidate::remainingSeats).containsExactly(10);
        read(round, 1);

        // then
        verify(source, times(1)).headers(anyLong(), any(), anyInt());
        verify(source, never()).restore(anyLong(), anyList(), anyInt());
        verifyNoInteractions(fallback);
    }

    @Test
    void 같은_수집_배치를_재시도해_관측이_교체되면_이전_시도의_캐시_관측을_정산에_사용하지_않는다() {
        // given
        memory.receive(1, batch(1, 2, 20));
        headers(header(1, 2));

        // when
        memory.receive(1, batch(1, 1, 10));

        // then
        assertThat(read(memory.begin(NOW), 1)).extracting(ArrivalCandidate::remainingSeats).containsExactly(20);
        verify(source, never()).restore(anyLong(), anyList(), anyInt());
    }

    @Test
    void 정산에_필요한_관측이_캐시에_빠져_있으면_DB에서_조회한다() {
        // given
        headers(header(1, 1));
        restoring(1, candidate(1, 10));

        // when
        List<ArrivalCandidate> result = read(memory.begin(NOW), 1);

        // then
        assertThat(result).containsExactly(candidate(1, 10));
        verify(source).restore(1, List.of(1L), 11);
        verifyNoInteractions(fallback);
    }

    @Test
    void 재시작으로_캐시가_비면_DB에_저장된_관측을_읽는다() {
        // given
        headers(new Header(1, 1, NOW, 1, 5));
        restoring(1, candidate(1, 10));

        // when
        read(memory.begin(NOW), 1);

        // then
        verify(source).restore(1, List.of(1L), 11);
    }

    @Test
    void 노선의_품질_버전이_바뀌면_이전_품질_버전으로_보관한_관측을_그대로_정산에_사용하지_않는다() {
        // given
        memory.receive(1, batch(1, 1, 10));
        headers(header(1, 1));
        ArrivalCandidate excluded = new ArrivalCandidate(1, candidate(1, 10).vehicle(), null, true);
        restoring(1, excluded);

        // when
        List<ArrivalCandidate> result = read(memory.begin(NOW), 2);

        // then
        assertThat(result).containsExactly(excluded);
        verify(source).restore(1, List.of(1L), 11);
    }

    @Test
    void 품질_변경을_반영한_캐시를_이전_품질_버전의_수집_콜백이_덮어쓰지_않는다() {
        // given
        headers(header(1, 1));
        ArrivalCandidate excluded = new ArrivalCandidate(1, candidate(1, 10).vehicle(), null, true);
        restoring(1, excluded);
        assertThat(read(memory.begin(NOW), 2)).containsExactly(excluded);

        // when
        final boolean accepted = memory.receive(1, batch(1, 1, 10));
        List<ArrivalCandidate> result = read(memory.begin(NOW), 2);

        // then
        assertThat(accepted).isFalse();
        assertThat(result).containsExactly(excluded);
        verify(source, times(1)).restore(anyLong(), anyList(), anyInt());
        verifyNoInteractions(fallback);
    }

    @Test
    void 같은_전달을_반복해도_관측이_늘어나지_않는다() {
        // given
        headers(header(1, 1));
        memory.receive(1, batch(1, 1, 10));

        // when
        memory.receive(1, batch(1, 1, 10));

        // then
        assertThat(read(memory.begin(NOW), 1)).hasSize(1);
    }

    @Test
    void 배치가_비워지면_늦은_콜백이_와도_옛_관측을_사용하지_않는다() {
        // given
        memory.receive(1, batch(1, 1, 10));
        memory.receive(1, new ObservationDeliveryBatch(1, 1, 2,
            Kind.CLEARED, null, List.of()));
        headers();

        // when
        memory.receive(1, batch(1, 1, 10));

        // then
        assertThat(read(memory.begin(NOW), 1)).isEmpty();
    }

    @Test
    void 보관_한도를_넘는_범위는_일부만_반환하지_않고_DB에서_읽는다() {
        // given
        ObservationDeliveryService small = memory(1);
        headers(header(1, 1), header(2, 1));
        when(fallback.findAfter(1, "bus", NOW.minusSeconds(60), 100)).thenReturn(List.of(candidate(2, 20)));

        // when
        List<ArrivalCandidate> result = read(small.begin(NOW), 1);

        // then
        assertThat(result).containsExactly(candidate(2, 20));
        verify(source, never()).restore(anyLong(), anyList(), anyInt());
    }

    @Test
    void 복원_도중_배치가_바뀌면_불완전한_결과를_사용하지_않는다() {
        // given
        headers(header(1, 1));
        when(source.restore(anyLong(), anyList(), anyInt())).thenReturn(List.of());

        // when
        read(memory.begin(NOW), 1);

        // then
        verify(fallback).findAfter(1, "bus", NOW.minusSeconds(60), 100);
    }

    @Test
    void 먼저_발급되고_늦게_커밋된_배치도_다음_회차에_포함한다() {
        // given
        memory.receive(1, batch(2, 1, 20));
        headers(header(2, 1));
        read(memory.begin(NOW), 1);

        // when
        memory.receive(1, batch(1, 1, 10));
        headers(header(2, 1), header(1, 1));

        // then
        assertThat(read(memory.begin(NOW), 1)).extracting(ArrivalCandidate::observationId).containsExactly(1L, 2L);
    }

    @Test
    void 회차_도중_새_관측이_전달돼도_확인한_목록만_사용한다() {
        // given
        memory.receive(1, batch(1, 1, 10));
        headers(header(1, 1));
        Round round = memory.begin(NOW);
        read(round, 1);

        // when
        memory.receive(1, batch(2, 1, 20));

        // then
        assertThat(read(round, 1)).extracting(ArrivalCandidate::observationId).containsExactly(1L);
    }

    @Test
    void 품질조사_중이거나_보관기간_밖이면_기존_DB_조회로_처리한다() {
        // given
        Round round = memory.begin(NOW);

        // when
        round.read(fallback, 1, 1, true, "bus", NOW, 100);
        round.read(fallback, 1, 1, false, "bus", NOW.minusSeconds(3600), 100);

        // then
        verifyNoInteractions(source);
        verify(fallback).findAfter(1, "bus", NOW, 100);
        verify(fallback).findAfter(1, "bus", NOW.minusSeconds(3600), 100);
    }

    private ObservationDeliveryService memory(final int capacity) {
        return new ObservationDeliveryService(source, Clock.fixed(NOW, ZoneOffset.UTC), capacity,
            Duration.ofMinutes(30));
    }

    private List<ArrivalCandidate> read(Round round, final long revision) {
        return round.read(fallback, 1, revision, false, "bus", NOW.minusSeconds(60), 100);
    }

    private void headers(Header... headers) {
        when(source.headers(anyLong(), any(), anyInt())).thenReturn(List.of(headers));
    }

    private Header header(final long batch, final int attempt) {
        return new Header(batch, attempt, NOW, 1, 5);
    }

    private ObservationDeliveryBatch batch(final long id, final int attempt, final int seats) {
        return new ObservationDeliveryBatch(id, 1, attempt, Kind.STORED, NOW,
            List.of(new Row(id, "bus", seats, 2, null, 2, 2)));
    }

    private ArrivalCandidate candidate(final long id, final int seats) {
        return new ArrivalCandidate(id, new ObservedVehicle("bus", 1, 2, NOW, seats, null), 0L, true);
    }

    private void restoring(final long batch, ArrivalCandidate candidate) {
        when(source.restore(anyLong(), anyList(), anyInt()))
            .thenReturn(List.of(new RecentObservationBatches.Row(batch, candidate)));
    }
}
