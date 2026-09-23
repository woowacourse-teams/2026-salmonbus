package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import com.gustler.backend.observations.api.CollectionInput;
import com.gustler.backend.observations.api.CollectionInputs;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForecastEvaluationWriterTest {

    private static final Instant ARRIVED_AT = Instant.parse("2026-08-19T02:20:00Z");
    private static final Instant SCORED_AT = ARRIVED_AT.plusSeconds(60);
    private static final int TARGET_STOP_ORDER = 9;

    @Mock
    private ForecastEvaluationRepository evaluations;

    @Mock
    private RouteDataQualityAccess quality;

    @Mock
    private CollectionInputs collectionInputs;

    @Mock
    private SameDayFullOutcomesService outcomes;

    private ForecastEvaluationWriter writer;

    @BeforeEach
    void 평가_저장_서비스를_준비한다() {
        writer = new ForecastEvaluationWriter(evaluations, quality, collectionInputs, outcomes);
    }

    @Test
    void 노선_순서대로_잠근_뒤_도착_입력을_확정하고_새_평가만_보정에_반영한다() {
        // given 요청 순서와 노선 순서가 달라도 잠금 순서는 일정하다.
        ForecastEvaluation first = settledEvaluation(200L, 300L, SCORED_AT);
        ForecastEvaluation second = settledEvaluation(100L, 400L, SCORED_AT.plusSeconds(1));
        List<ForecastEvaluation> completed = List.of(first, second);
        List<SettledForecast> newlySettled = List.of(
            new SettledForecast(8L, 3, 0.4, ARRIVED_AT, 0),
            new SettledForecast(3L, 3, 0.6, ARRIVED_AT, 0));
        when(evaluations.findRouteIdsForObservations(List.of(200L, 100L))).thenReturn(List.of(8L, 3L, 8L));
        when(evaluations.canComplete(first)).thenReturn(true);
        when(evaluations.canComplete(second)).thenReturn(true);
        when(collectionInputs.lockForObservation(300L))
            .thenReturn(new CollectionInput(601L, 91L, 4, ARRIVED_AT, true, false));
        when(collectionInputs.lockForObservation(400L))
            .thenReturn(new CollectionInput(602L, 92L, 2, ARRIVED_AT, true, false));
        when(evaluations.settle(List.of(first))).thenReturn(List.of(newlySettled.get(0)));
        when(evaluations.settle(List.of(second))).thenReturn(List.of(newlySettled.get(1)));

        // when
        List<SettledForecast> actual = writer.complete(completed);

        // then
        assertThat(actual).containsExactlyElementsOf(newlySettled);
        InOrder ordered = inOrder(evaluations, quality, collectionInputs, outcomes);
        ordered.verify(evaluations).findRouteIdsForObservations(List.of(200L, 100L));
        ordered.verify(quality).lockByRoute(3L);
        ordered.verify(quality).lockByRoute(8L);
        ordered.verify(evaluations).canComplete(first);
        ordered.verify(collectionInputs).lockForObservation(300L);
        ordered.verify(collectionInputs).confirmInput(601L, 4, SCORED_AT);
        ordered.verify(evaluations).settle(List.of(first));
        ordered.verify(evaluations).canComplete(second);
        ordered.verify(collectionInputs).lockForObservation(400L);
        ordered.verify(collectionInputs).confirmInput(602L, 2, SCORED_AT.plusSeconds(1));
        ordered.verify(evaluations).settle(List.of(second));
        ordered.verify(outcomes).record(newlySettled);
        ordered.verifyNoMoreInteractions();
    }

    @Test
    void 완료할_수_없는_평가는_도착_입력을_확정하거나_집계에_더하지_않는다() {
        // given 이미 완료됐거나 현재 자료 품질 조건에 맞지 않는 평가다.
        ForecastEvaluation completed = settledEvaluation(200L, 300L, SCORED_AT);
        when(evaluations.findRouteIdsForObservations(List.of(200L))).thenReturn(List.of(8L));
        when(evaluations.canComplete(completed)).thenReturn(false);

        // when
        List<SettledForecast> actual = writer.complete(List.of(completed));

        // then
        assertThat(actual).isEmpty();
        verify(quality).lockByRoute(8L);
        verifyNoInteractions(collectionInputs, outcomes);
        verify(evaluations, never()).settle(anyList());
    }

    @Test
    void 도착_관측이_없는_종료_결과는_입력_확정_없이_저장한다() {
        // given
        ForecastEvaluation skipped = ForecastEvaluation.completed(200L, TARGET_STOP_ORDER,
            new ArrivalLabel.Skipped(), SCORED_AT);
        when(evaluations.findRouteIdsForObservations(List.of(200L))).thenReturn(List.of(8L));
        when(evaluations.canComplete(skipped)).thenReturn(true);

        // when
        List<SettledForecast> actual = writer.complete(List.of(skipped));

        // then
        assertThat(actual).isEmpty();
        verify(evaluations).settle(List.of(skipped));
        verifyNoInteractions(collectionInputs, outcomes);
    }

    @Test
    void 같은_평가가_한_요청에_두_번_있어도_두_번째_도착_입력은_확정하지_않는다() {
        // given 첫 번째 저장 뒤에는 같은 평가를 더 이상 완료할 수 없다.
        ForecastEvaluation first = settledEvaluation(200L, 300L, SCORED_AT);
        ForecastEvaluation repeated = settledEvaluation(200L, 400L, SCORED_AT.plusSeconds(1));
        SettledForecast newlySettled = new SettledForecast(8L, 3, 0.4, ARRIVED_AT, 0);
        when(evaluations.findRouteIdsForObservations(List.of(200L))).thenReturn(List.of(8L));
        when(evaluations.canComplete(first)).thenReturn(true);
        when(evaluations.canComplete(repeated)).thenReturn(false);
        when(collectionInputs.lockForObservation(300L))
            .thenReturn(new CollectionInput(601L, 91L, 4, ARRIVED_AT, true, false));
        when(evaluations.settle(List.of(first))).thenReturn(List.of(newlySettled));

        // when
        List<SettledForecast> actual = writer.complete(List.of(first, repeated));

        // then 두 번째 완료 가능 여부를 첫 번째 저장 이후에 확인해야 한다.
        assertThat(actual).containsExactly(newlySettled);
        InOrder ordered = inOrder(evaluations, collectionInputs, outcomes);
        ordered.verify(evaluations).canComplete(first);
        ordered.verify(collectionInputs).lockForObservation(300L);
        ordered.verify(collectionInputs).confirmInput(601L, 4, SCORED_AT);
        ordered.verify(evaluations).settle(List.of(first));
        ordered.verify(evaluations).canComplete(repeated);
        ordered.verify(outcomes).record(List.of(newlySettled));
        verify(collectionInputs, never()).lockForObservation(400L);
        verify(evaluations, never()).settle(List.of(repeated));
    }

    private static ForecastEvaluation settledEvaluation(
        final long sourceId,
        final long arrivalId,
        Instant scoredAt
    ) {
        return ForecastEvaluation.completed(sourceId, TARGET_STOP_ORDER,
            new ArrivalLabel.Settled(arrivalId, 0), scoredAt);
    }
}
