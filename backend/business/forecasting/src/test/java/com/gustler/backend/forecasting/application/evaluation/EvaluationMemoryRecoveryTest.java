package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Key;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Restored;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.quality.WorkerQualityState;
import com.gustler.backend.forecasting.application.quality.WorkerQualityState.Snapshot;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 순회·캐시·커밋 콜백 단위 테스트. 실제 DB 원자성은 별도 통합 테스트에서 검증한다. */
class EvaluationMemoryRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:30Z");
    private final EvaluationWorkStore work = mock(EvaluationWorkStore.class);
    private final ArrivalObservationQuery arrivals = mock(ArrivalObservationQuery.class);
    private final RouteDataQualityAccess quality = mock(RouteDataQualityAccess.class);
    private final ForecastEvaluationWriter writer = mock(ForecastEvaluationWriter.class);
    private final TreeMap<Long, PendingForecast> inputs = new TreeMap<>();
    private final List<String> evaluatedVehicles = new ArrayList<>();
    private EvaluateForecastsService service;

    @Test
    void 품질이_바뀌지_않으면_다음_정산에서_입력_준비용_품질_SELECT를_실행하지_않는다() {
        // given
        add(10);
        AtomicInteger reads = new AtomicInteger();
        service.useQualityMemory(new WorkerQualityState(version -> {
            reads.incrementAndGet();
            return new Snapshot(1, Set.of());
        }, 20));

        // when
        commitRound();
        commitRound();

        // then
        assertThat(reads.get()).isEqualTo(1);
        verify(quality, Mockito.never()).current(anyLong());
        verify(quality, Mockito.never()).anyInvestigationPending(anyLong());
        verify(quality, times(2)).lock(1);
        assertThat(evaluatedVehicles).containsExactly("bus-10", "bus-10");
    }

    @Test
    void 품질_상태를_읽지_못하면_DB에서_확인하고_대기_상세도_다시_읽는다() {
        // given
        add(10);
        commitRound();
        service.useQualityMemory(new WorkerQualityState(version -> {
            throw new IllegalStateException("품질 조회 실패");
        }, 20));

        // when
        commitRound();

        // then
        verify(quality, times(2)).current(1);
        verify(work, times(2)).restore(1, List.of(10L));
    }

    @Test
    void 정산_계산_중_품질이_바뀌어도_노선_전체의_완료_확인을_건너뛰지_않는다() {
        // given
        add(10);
        WorkerQualityState state = new WorkerQualityState(version -> new Snapshot(1, Set.of()), 20);
        service.useQualityMemory(state);
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenAnswer(call -> {
            state.beginChange().close();
            return List.of();
        });

        // when
        commitRound();

        // then
        verify(writer).complete(List.of());
        verify(work).releaseCompleted(1, List.of(10L));
    }

    @BeforeEach
    void 대기가_계속_남는_정산_회차를_준비한다() {
        when(work.routes()).thenReturn(List.of(1L));
        when(quality.lock(1)).thenReturn(1L);
        when(quality.current(1)).thenReturn(1L);
        when(work.lastObservationId(1)).thenAnswer(call -> {
            if (inputs.isEmpty()) {
                return 0L;
            }
            return (long) inputs.lastKey();
        });
        when(work.page(anyLong(), anyLong(), anyLong(), anyInt())).thenAnswer(call -> {
            final long after = call.getArgument(1);
            final long through = call.getArgument(2);
            final int limit = call.getArgument(3);
            return inputs.keySet()
                .stream()
                .filter(id -> id > after && id <= through)
                .limit(limit)
                .toList();
        });
        when(work.revisions(anyLong(), any())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(1);
            return ids.stream()
                .collect(Collectors.toMap(id -> id, id -> 1L));
        });
        when(work.restore(anyLong(), any())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(1);
            return ids.stream()
                .map(id -> new Restored(
                    new Key(id, 2), Optional.of(inputs.get(id))))
                .toList();
        });
        when(arrivals.findAfter(anyLong(), anyString(), any(), anyInt())).thenAnswer(call -> {
            evaluatedVehicles.add(call.getArgument(1));
            return List.of();
        });
        service = newService();
    }

    @Test
    void 정산이_취소되면_같은_작업부터_다시_평가한다() {
        // given
        add(10);
        add(20);
        finish(prepareRound(), TransactionSynchronization.STATUS_ROLLED_BACK);

        // when
        commitRound();

        // then
        assertThat(evaluatedVehicles).containsExactly("bus-10", "bus-10");
        verify(work, times(2)).restore(1, List.of(10L, 20L));
    }

    @Test
    void 메모리가_가득_차도_아직_평가하지_않은_작업을_처리한다() {
        // given
        for (long id = 1; id <= 17; id++) {
            add(id);
        }

        // when
        for (int round = 0; round < 17; round++) {
            commitRound();
        }

        // then
        assertThat(evaluatedVehicles).containsExactlyElementsOf(inputs.keySet()
            .stream()
            .map(id -> "bus-" + id)
            .toList());
        verify(work, times(17)).releaseCompleted(anyLong(), any());
    }

    @Test
    void 새_작업이_늘어도_정해둔_끝점에서_처음으로_돌아간다() {
        // given
        add(10);
        add(20);
        commitRound();
        add(30);

        // when
        commitRound();
        commitRound();

        // then
        assertThat(evaluatedVehicles).containsExactly("bus-10", "bus-20", "bus-10");
    }

    @Test
    void 늦게_저장된_작은_관측_ID도_다음_순회에서_찾는다() {
        // given
        add(10);
        add(20);
        commitRound();
        add(5);

        // when
        commitRound();
        commitRound();

        // then
        assertThat(evaluatedVehicles).containsExactly("bus-10", "bus-20", "bus-5");
    }

    @Test
    void 오래된_회차의_콜백이_새_순회_위치를_덮어쓰지_않는다() {
        // given
        add(10);
        add(20);
        List<TransactionSynchronization> older = prepareRound();
        commitRound();
        commitRound();

        // when
        finish(older, TransactionSynchronization.STATUS_COMMITTED);
        commitRound();

        // then
        assertThat(evaluatedVehicles).containsExactly("bus-10", "bus-10", "bus-20", "bus-10");
    }

    @Test
    void 서버가_다시_시작하면_DB의_작업을_다시_불러온다() {
        // given
        add(10);
        commitRound();
        service = newService();

        // when
        commitRound();

        // then
        assertThat(evaluatedVehicles).containsExactly("bus-10", "bus-10");
        verify(work, times(2)).restore(1, List.of(10L));
    }

    @Test
    void 품질이_바뀌어도_다른_대기를_처리하고_남은_작업은_다음_순회에서_다시_확인한다() {
        // given
        add(10);
        add(20);
        when(quality.lock(1)).thenReturn(2L);

        // when
        commitRound();
        when(quality.current(1)).thenReturn(2L);
        commitRound();
        commitRound();

        // then
        assertThat(evaluatedVehicles).containsExactly("bus-10", "bus-20", "bus-10");
        verify(work, times(2)).releaseCompleted(1, List.of(10L, 20L));
        verify(work).releaseCompleted(1, List.of(20L));
    }

    @Test
    void 커밋_성공_여부를_알_수_없으면_메모리를_비우고_DB에서_다시_복원한다() {
        // given
        add(10);
        add(20);
        commitRound();

        // when
        finish(prepareRound(), TransactionSynchronization.STATUS_UNKNOWN);
        commitRound();

        // then
        assertThat(evaluatedVehicles).containsExactly("bus-10", "bus-20", "bus-10");
        verify(work, times(2)).restore(1, List.of(10L, 20L));
    }

    private EvaluateForecastsService newService() {
        return new EvaluateForecastsService(work, new EvaluationWorkPageReader(work),
            new EvaluationRouteCommitter(work, quality, writer,
                new EvaluationQualityRecheck(work, arrivals, 400)), arrivals,
            quality, new ForecastPolicy(Duration.ofMinutes(5), 20, 1, 400),
            Clock.fixed(NOW, ZoneOffset.UTC), 12, 1,
            new RecentArrivalObservationCache(false, 30000, Duration.ofMinutes(30)));
    }

    private void add(final long id) {
        inputs.put(id, new PendingForecast(id, 2, 1, "bus-" + id, 1,
            NOW.minusSeconds(30), NOW.minusSeconds(30)));
    }

    private void commitRound() {
        finish(prepareRound(), TransactionSynchronization.STATUS_COMMITTED);
    }

    private List<TransactionSynchronization> prepareRound() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.settleArrivalLabels();
            return List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static void finish(List<TransactionSynchronization> callbacks, final int status) {
        callbacks.forEach(callback -> callback.afterCompletion(status));
    }
}
