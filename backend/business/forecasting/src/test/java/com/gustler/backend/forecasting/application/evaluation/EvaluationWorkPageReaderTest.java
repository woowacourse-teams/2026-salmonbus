package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkPageReader.Page;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Key;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Restored;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class EvaluationWorkPageReaderTest {
    private final EvaluationWorkStore work = mock(EvaluationWorkStore.class);
    private final EvaluationWorkPageReader reader = new EvaluationWorkPageReader(work);

    @BeforeEach
    void 작업의_현재_갱신_번호를_준비한다() {
        Mockito.lenient().when(work.revisions(1, List.of(10L))).thenReturn(Map.of(10L, 1L));
    }

    @Test
    void 같은_관측의_예보가_갱신되면_메모리를_재사용하지_않는다() {
        // given
        PendingEvaluationBuffer buffer = cached();
        when(work.page(1, 0, 10, 100)).thenReturn(List.of(10L));
        when(work.revisions(1, List.of(10L))).thenReturn(Map.of(10L, 2L));
        when(work.restore(1, List.of(10L))).thenReturn(List.of(
            new Restored(new Key(10, 2), Optional.of(forecast()))));

        // when
        Page page = reader.read(1, 0, 10, 100, 1, false, Map.of(10L, buffer.next(1).getFirst()));

        // then
        verify(work).restore(1, List.of(10L));
        assertThat(page.ready().getFirst().workRevision()).isEqualTo(2);
    }

    @Test
    void 메모리의_품질_정보가_같으면_대기_상세를_다시_읽지_않는다() {
        // given
        PendingEvaluationBuffer buffer = cached();
        when(work.page(1, 0, 10, 100)).thenReturn(List.of(10L));

        // when
        Page page = reader.read(1, 0, 10, 100, 1, false, Map.of(10L, buffer.next(1).getFirst()));

        // then
        assertThat(page.ready().getFirst().forecasts()).containsExactly(forecast());
        verify(work, never()).restore(anyLong(), anyList());
    }

    @Test
    void 품질_정보가_바뀌면_메모리_대신_DB에서_다시_읽는다() {
        // given
        PendingEvaluationBuffer buffer = cached();
        when(work.page(1, 0, 10, 100)).thenReturn(List.of(10L));
        when(work.restore(1, List.of(10L))).thenReturn(List.of(
            new Restored(new Key(10, 2),
                Optional.of(forecast()))));

        // when
        Page page = reader.read(1, 0, 10, 100, 2, false, Map.of(10L, buffer.next(1).getFirst()));

        // then
        verify(work).restore(1, List.of(10L));
        assertThat(page.ready().getFirst().qualityRevision()).isEqualTo(2);
    }

    @Test
    void 품질_조사로_상세를_읽을_수_없으면_평가하지_않고_보류한다() {
        // given
        PendingEvaluationBuffer buffer = cached();
        when(work.page(1, 0, 10, 100)).thenReturn(List.of(10L));
        when(work.restore(1, List.of(10L))).thenReturn(List.of(
            new Restored(new Key(10, 2), Optional.empty())));

        // when
        Page page = reader.read(1, 0, 10, 100, 1, true, Map.of(10L, buffer.next(1).getFirst()));

        // then
        assertThat(page.checkedIds()).containsExactly(10L);
        assertThat(page.ready()).isEmpty();
        assertThat(buffer.size()).isEqualTo(1);
    }

    @Test
    void 마지막_작업까지_확인하면_처음부터_다시_찾도록_순회_위치를_되돌린다() {
        // given
        when(work.page(1, 10, 10, 100)).thenReturn(List.of());

        // when
        Page page = reader.read(1, 10, 10, 100, 1, false, Map.of());

        // then
        assertThat(page.nextAfter()).isZero();
        assertThat(page.checkedIds()).isEmpty();
        verify(work, never()).restore(anyLong(), anyList());
    }

    @Test
    void 대기를_읽는_단계에서는_품질_제외_종료를_저장하지_않는다() {
        // given
        when(work.page(1, 0, 10, 100)).thenReturn(List.of(10L));
        when(work.restore(1, List.of(10L))).thenReturn(List.of(
            new Restored(new Key(10, 2), Optional.empty())));

        // when
        Page page = reader.read(1, 0, 10, 100, 1, false, Map.of());

        // then
        assertThat(page.workRevisions()).containsEntry(10L, 1L);
        verify(work, never()).closeExcluded(anyLong(), anyList(), ArgumentMatchers.anyInt());
    }

    private static PendingEvaluationBuffer cached() {
        PendingEvaluationBuffer buffer = new PendingEvaluationBuffer(12);
        buffer.restore(1, List.of(forecast()));
        return buffer;
    }

    private static PendingForecast forecast() {
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        return new PendingForecast(10, 2, 1, "vehicle", 1, at, at);
    }
}
