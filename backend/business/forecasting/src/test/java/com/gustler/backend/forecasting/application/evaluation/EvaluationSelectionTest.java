package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.application.evaluation.EvaluationSelection.Cursor;
import com.gustler.backend.forecasting.application.evaluation.EvaluationSelection.Selected;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkPageReader.Page;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkPageReader.Ready;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationSelectionTest {
    @Test
    void 한_관측을_일부만_평가했다면_다음_정류장부터_이어간다() {
        // given
        Page page = page();
        Cursor start = new Cursor(10, 0, 0, 0);
        Selected first = EvaluationSelection.take(start, page, 1);

        // when
        Selected next = EvaluationSelection.take(first.next(), page, 1);

        // then
        assertThat(first.forecasts()).extracting(PendingForecast::targetStopOrder).containsExactly(2);
        assertThat(next.forecasts()).extracting(PendingForecast::targetStopOrder).containsExactly(3);
    }

    @Test
    void 정해둔_마지막_관측까지_평가하면_처음부터_다시_확인한다() {
        // given
        Cursor cursor = new Cursor(10, 0, 10, 3);

        // when
        Selected selected = EvaluationSelection.take(cursor, page(), 1);

        // then
        assertThat(selected.forecasts()).extracting(PendingForecast::targetStopOrder).containsExactly(4);
        assertThat(selected.next()).isEqualTo(Cursor.START);
    }

    @Test
    void 보류된_관측이_있어도_다음_관측을_평가한다() {
        // given
        Ready ready = new Ready(20, 1, List.of(forecast(20, 2)));
        Page page = new Page(20, List.of(10L, 20L), List.of(ready));

        // when
        Selected selected = EvaluationSelection.take(new Cursor(20, 0, 0, 0), page, 1);

        // then
        assertThat(selected.forecasts()).extracting(PendingForecast::vehicleObservationId).containsExactly(20L);
    }

    @Test
    void 남은_작업이_없으면_새_순회를_준비한다() {
        // given
        Page page = new Page(0, List.of(), List.of());

        // when
        Selected selected = EvaluationSelection.take(new Cursor(20, 10, 0, 0), page, 1);

        // then
        assertThat(selected.forecasts()).isEmpty();
        assertThat(selected.next()).isEqualTo(Cursor.START);
    }

    private static Page page() {
        Ready ready = new Ready(10, 1,
            List.of(forecast(10, 2), forecast(10, 3), forecast(10, 4)));
        return new Page(10, List.of(10L), List.of(ready));
    }

    private static PendingForecast forecast(final long observation, final int target) {
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        return new PendingForecast(observation, target, 1, "vehicle", target - 1, at, at);
    }
}
