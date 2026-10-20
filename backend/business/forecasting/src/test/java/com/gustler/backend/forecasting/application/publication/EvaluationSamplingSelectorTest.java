package com.gustler.backend.forecasting.application.publication;

import static org.assertj.core.api.Assertions.assertThat;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationSamplingPoint;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationSamplingSelectorTest {
    private static final Instant START = Instant.parse("2026-10-19T00:00:00Z");

    @Test
    void 선택만_하고_커밋하지_않으면_다음_예보도_전체_평가한다() {
        var selector = selector(true, "");

        selector.select(publication(0), sources(0, "UP"));
        var next = selector.select(publication(15), sources(15, "UP"));

        assertThat(next.publication().predictions()).allMatch(SeatForecast::evaluationDetailRequired);
    }

    @Test
    void 커밋한_표본부터_60초가_지나기_전에는_다음_정류장만_평가하고_예보값은_유지한다() {
        var selector = selector(true, "3330");
        selector.committed(selector.select(publication(0), sources(0, "UP")));

        var original = publication(15);
        var next = selector.select(original, sources(15, "UP"));
        selector.committed(next);
        var afterMinute = selector.select(publication(60), sources(60, "UP"));

        assertThat(next.publication().predictions()).extracting(SeatForecast::evaluationDetailRequired)
            .containsExactly(true, false);
        assertThat(next.publication()
            .predictions()
            .stream()
            .map(p -> p.withEvaluationDetailRequired(true))
            .toList())
            .isEqualTo(original.predictions());
        assertThat(afterMinute.publication().predictions()).allMatch(SeatForecast::evaluationDetailRequired);
    }

    @Test
    void 비활성화하거나_대상_노선이_아니면_전체_평가한다() {
        for (var selector : List.of(selector(false, ""), selector(true, "1650"))) {
            selector.committed(selector.select(publication(0), sources(0, "UP")));

            var next = selector.select(publication(15), sources(15, "UP"));

            assertThat(next.publication().predictions()).allMatch(SeatForecast::evaluationDetailRequired);
        }
    }

    @Test
    void 방향이나_관측_정보가_없으면_평가를_줄이지_않는다() {
        var selector = selector(true, "");
        selector.committed(selector.select(publication(0), sources(0, "UP")));

        var unknown = selector.select(publication(15), sources(15, "UNKNOWN"));
        var missing = selector.select(publication(15), List.of());

        assertThat(unknown.publication().predictions()).allMatch(SeatForecast::evaluationDetailRequired);
        assertThat(missing.publication().predictions()).allMatch(SeatForecast::evaluationDetailRequired);
    }

    @Test
    void 늦게_끝난_이전_커밋이_선택_기준_시각을_되돌리지_않는다() {
        var selector = selector(true, "");
        var old = selector.select(publication(0), sources(0, "UP"));
        selector.committed(selector.select(publication(60), sources(60, "UP")));

        selector.committed(old);
        var next = selector.select(publication(75), sources(75, "UP"));

        assertThat(next.publication().predictions()).extracting(SeatForecast::evaluationDetailRequired)
            .containsExactly(true, false);
    }

    private EvaluationSamplingSelector selector(boolean enabled, String routes) {
        return new EvaluationSamplingSelector(enabled, routes, Duration.ofSeconds(60));
    }

    private ForecastPublication publication(int seconds) {
        Instant at = START.plusSeconds(seconds);
        return new ForecastPublication(1, 1, 1, 0, 1, at, at, at, List.of(
            new SeatForecast(1, 1, 4, 1, 1, 0, .3, .3, 10.0, at),
            new SeatForecast(1, 1, 5, 2, 1, 0, .3, .3, 10.0, at)));
    }

    private List<EvaluationSamplingSource> sources(int seconds, String direction) {
        return List.of(new EvaluationSamplingSource(1, "3330",
            new EvaluationSamplingPoint(1, 1, "vehicle-1", 3, direction, START.plusSeconds(seconds))));
    }
}
