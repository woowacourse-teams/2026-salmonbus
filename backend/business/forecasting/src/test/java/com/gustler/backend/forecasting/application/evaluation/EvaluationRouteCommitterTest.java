package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.EvaluateForecastsService.RouteInput;
import com.gustler.backend.forecasting.application.evaluation.EvaluationRouteCommitter.Change;
import com.gustler.backend.forecasting.application.evaluation.EvaluationSelection.Cursor;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkPageReader.Ready;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Key;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Lost;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EvaluationRouteCommitterTest {
    private final EvaluationWorkStore work = mock(EvaluationWorkStore.class);
    private final RouteDataQualityAccess quality = mock(RouteDataQualityAccess.class);
    private final ForecastEvaluationWriter writer = mock(ForecastEvaluationWriter.class);
    private final EvaluationQualityRecheck recheck = mock(EvaluationQualityRecheck.class);
    private final EvaluationRouteCommitter committer = new EvaluationRouteCommitter(work, quality, writer, recheck);
    private final List<Integer> completions = new ArrayList<>();
    private final List<Change> changes = new ArrayList<>();
    private final Instant now = Instant.parse("2026-10-01T00:00:00Z");

    @BeforeEach
    void 커밋_콜백을_관찰할_트랜잭션을_준비한다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void 테스트_트랜잭션_상태를_정리한다() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void 계산_후_품질이_바뀌면_다시_확인한_결과만_저장한다() {
        // given
        when(quality.lock(1)).thenReturn(2L);
        when(work.revisions(1, List.of(10L))).thenReturn(Map.of(10L, 1L));
        ForecastEvaluation safe = ForecastEvaluation.completed(10, 2, new Lost(), now);
        when(recheck.unchanged(any(), any())).thenReturn(List.of(safe));

        // when
        commit();
        finish(TransactionSynchronization.STATUS_COMMITTED);

        // then
        InOrder order = inOrder(quality, recheck, writer);
        order.verify(quality).lock(1);
        order.verify(recheck).unchanged(any(), any());
        order.verify(writer).complete(List.of(safe));
        verify(work).releaseCompleted(1, List.of(10L));
        assertThat(completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(changes).singleElement().satisfies(change -> assertThat(change.remaining()).isEmpty());
    }

    @Test
    void DB_버전이_같아도_계산_중_품질_변경이_시작됐으면_자료를_다시_확인한다() {
        // given
        unchanged();

        // when
        commit(true);

        // then
        verify(recheck).unchanged(any(), any());
        verify(writer).complete(List.of());
    }

    @Test
    void 품질_재확인에_실패하면_결과와_작업_완료를_저장하지_않는다() {
        // given
        unchanged();
        when(recheck.unchanged(any(), any())).thenThrow(new IllegalStateException("DB 조회 실패"));

        // when
        Throwable caughtFailure = catchThrowable(() -> commit(true));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
        verify(work, never()).closeExcluded(anyLong(), any(), anyInt());
        verify(work, never()).releaseCompleted(anyLong(), any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void 계산_후_같은_관측의_예보가_갱신되면_정산과_메모리_반영을_건너뛴다() {
        // given
        when(quality.lock(1)).thenReturn(1L);
        when(work.revisions(1, List.of(10L))).thenReturn(Map.of(10L, 2L));

        // when
        commit();
        finish(TransactionSynchronization.STATUS_COMMITTED);

        // then
        verifyNoInteractions(writer);
        verify(work, never()).releaseCompleted(anyLong(), any());
        assertThat(completions).isEmpty();
    }

    @Test
    void 다른_회차가_이미_종료한_작업이면_오래된_계산을_저장하지_않는다() {
        // given
        when(quality.lock(1)).thenReturn(1L);
        when(work.revisions(1, List.of(10L))).thenReturn(Map.of());

        // when
        commit();

        // then
        verifyNoInteractions(writer);
        verify(work, never()).releaseCompleted(anyLong(), any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void 품질_제외로_종료한_예보는_도착_정산으로_다시_저장하지_않는다() {
        // given
        unchanged();
        when(work.completed(1, List.of(10L))).thenReturn(List.of(new Key(10, 2)));

        // when
        commit();

        // then
        InOrder order = inOrder(work, writer);
        order.verify(work).closeExcluded(1, List.of(10L), 500);
        order.verify(work).completed(1, List.of(10L));
        order.verify(writer).complete(List.of());
        order.verify(work).completed(1, List.of(10L));
        order.verify(work).releaseCompleted(1, List.of(10L));
        assertThat(completions).isEmpty();
    }

    @Test
    void 정산_저장이_끝나도_트랜잭션이_커밋되기_전에는_메모리_반영을_요청하지_않는다() {
        // given
        unchanged();

        // when
        commit();

        // then
        assertThat(completions).isEmpty();
        finish(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
        verifyNoInteractions(recheck);
    }

    private void unchanged() {
        when(quality.lock(1)).thenReturn(1L);
        when(work.revisions(1, List.of(10L))).thenReturn(Map.of(10L, 1L));
    }

    private void commit() {
        commit(false);
    }

    private void commit(final boolean memoryChanged) {
        PendingForecast forecast = new PendingForecast(10, 2, 1, "bus", 1, now, now);
        RouteInput input = new RouteInput(1, 1, false,
            Cursor.START, List.of(10L), Map.of(10L, 1L),
            List.of(new Ready(10, 1, 1, List.of(forecast))), List.of(forecast));
        committer.commit(input, List.of(ForecastEvaluation.completed(10, 2, new Lost(), now)),
            (status, change) -> {
                completions.add(status);
                changes.add(change);
            }, memoryChanged);
    }

    private static void finish(final int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCompletion(status));
    }
}
