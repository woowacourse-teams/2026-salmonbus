package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.evaluation.SettledEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import com.gustler.backend.forecasting.domain.statistics.DemandSample;
import com.gustler.backend.forecasting.domain.statistics.DemandSampleRepository;
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
    private SameDayFullOutcomesService outcomes;

    @Mock
    private DemandSampleRepository samples;

    private ForecastEvaluationWriter writer;

    @BeforeEach
    void 평가_저장_서비스를_준비한다() {
        writer = new ForecastEvaluationWriter(evaluations, quality, outcomes, samples);
    }

    @Test
    void 노선_순서대로_잠근_뒤_평가를_저장하고_새_평가만_보정에_반영한다() {
        // given 요청 순서와 노선 순서가 달라도 잠금 순서는 일정하다.
        ForecastEvaluation first = settledEvaluation(200L, 300L, SCORED_AT);
        ForecastEvaluation second = settledEvaluation(100L, 400L, SCORED_AT.plusSeconds(1));
        List<ForecastEvaluation> completed = List.of(first, second);
        List<SettledForecast> newlySettled = List.of(
            new SettledForecast(8L, 3, 0.4, ARRIVED_AT, 0),
            new SettledForecast(3L, 3, 0.6, ARRIVED_AT, 0));
        when(evaluations.findRouteIdsForObservations(List.of(200L, 100L))).thenReturn(List.of(8L, 3L, 8L));
        when(evaluations.settle(completed)).thenReturn(List.of(
            settledFrom(newlySettled.get(0)), settledFrom(newlySettled.get(1))));

        // when
        List<SettledForecast> actual = writer.complete(completed);

        // then
        assertThat(actual).containsExactlyElementsOf(newlySettled);
        InOrder ordered = inOrder(evaluations, quality, outcomes);
        ordered.verify(evaluations).findRouteIdsForObservations(List.of(200L, 100L));
        ordered.verify(quality).lockByRoute(3L);
        ordered.verify(quality).lockByRoute(8L);
        ordered.verify(evaluations).settle(completed);
        ordered.verify(outcomes).record(newlySettled);
        ordered.verifyNoMoreInteractions();
    }

    @Test
    void 새로_확정한_평가가_없으면_당일_성적에_더하지_않는다() {
        // given 이미 완료됐거나 현재 자료 품질 조건에 맞지 않는 평가다.
        ForecastEvaluation completed = settledEvaluation(200L, 300L, SCORED_AT);
        when(evaluations.findRouteIdsForObservations(List.of(200L))).thenReturn(List.of(8L));
        when(evaluations.settle(List.of(completed))).thenReturn(List.of());

        // when
        List<SettledForecast> actual = writer.complete(List.of(completed));

        // then
        assertThat(actual).isEmpty();
        verify(quality).lockByRoute(8L);
        verifyNoInteractions(outcomes, samples);
    }

    @Test
    void 도착_관측이_없는_종료_결과도_저장한다() {
        // given
        ForecastEvaluation skipped = ForecastEvaluation.completed(200L, TARGET_STOP_ORDER,
            new ArrivalLabel.Skipped(), SCORED_AT);
        when(evaluations.findRouteIdsForObservations(List.of(200L))).thenReturn(List.of(8L));

        // when
        List<SettledForecast> actual = writer.complete(List.of(skipped));

        // then
        assertThat(actual).isEmpty();
        verify(evaluations).settle(List.of(skipped));
        verifyNoInteractions(outcomes);
    }

    @Test
    void 승차_정류장_한_곳_앞의_정산은_통계_입력으로_기록한다() {
        // given
        ForecastEvaluation settled = settledEvaluation(200L, 300L, SCORED_AT);
        when(evaluations.findRouteIdsForObservations(List.of(200L))).thenReturn(List.of(8L));
        when(evaluations.settle(List.of(settled))).thenReturn(List.of(new SettledEvaluation(8L, 11L, 200L,
            TARGET_STOP_ORDER, 1, 0.4, ScoringState.SETTLED, 300L, 0, ARRIVED_AT, SCORED_AT, true, "bus-1", 12,
            true)));

        // when
        writer.complete(List.of(settled));

        // then
        verify(samples).record(List.of(new DemandSample(11L, 200L, 300L, "bus-1", TARGET_STOP_ORDER, ARRIVED_AT,
            SCORED_AT, 12, 0)));
    }

    private static SettledEvaluation settledFrom(
        SettledForecast forecast
    ) {
        return new SettledEvaluation(forecast.routeId(), 11L, 200L, TARGET_STOP_ORDER, forecast.stopsToTarget(),
            forecast.rawFullChance(), ScoringState.SETTLED, 300L, forecast.seatsOnArrival(), forecast.arrivedAt(),
            SCORED_AT, true, null, null, false);
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
