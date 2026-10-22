package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class PendingEvaluationBufferTest {
    @Test
    void 정산이_끝난_예보만_대기에서_제거한다() {
        // given
        PendingEvaluationBuffer buffer = new PendingEvaluationBuffer(12);
        buffer.restore(1, List.of(forecast(1, 1), forecast(1, 2)));
        PendingEvaluationBuffer.Entry evaluated = buffer.next(1).getFirst();

        // when
        buffer.committed(evaluated, Set.of(1));

        // then
        assertThat(buffer.size()).isEqualTo(1);
        assertThat(buffer.next(1).getFirst().forecasts())
            .extracting(PendingForecast::targetStopOrder).containsExactly(2);
    }

    @Test
    void 정산이_끝나지_않은_예보는_다음_차례에도_평가한다() {
        // given
        PendingEvaluationBuffer buffer = new PendingEvaluationBuffer(12);
        buffer.restore(1, List.of(forecast(1, 1)));
        PendingEvaluationBuffer.Entry before = buffer.next(1).getFirst();

        // when
        PendingEvaluationBuffer.Entry retry = buffer.next(1).getFirst();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(retry).isSameAs(before);
            softly.assertThat(buffer.size()).isEqualTo(1);
        }
    }

    @Test
    void 대기를_새로_읽은_뒤에는_이전_처리_결과로_지우지_않는다() {
        // given
        PendingEvaluationBuffer buffer = new PendingEvaluationBuffer(12);
        buffer.restore(1, List.of(forecast(1, 1)));
        PendingEvaluationBuffer.Entry old = buffer.next(1).getFirst();
        buffer.restore(2, List.of(forecast(1, 1)));

        // when
        buffer.committed(old, Set.of(1));
        buffer.invalidate(old);

        // then
        assertThat(buffer.size()).isEqualTo(1);
        assertThat(buffer.next(1).getFirst().qualityRevision()).isEqualTo(2);
    }

    @Test
    void 보관_한도를_넘는_대기는_메모리에_넣지_않고_기존_대기는_유지한다() {
        // given
        PendingEvaluationBuffer buffer = new PendingEvaluationBuffer(12);
        List<PendingForecast> full = IntStream.rangeClosed(1, 12)
            .mapToObj(target -> forecast(1, target))
            .toList();
        buffer.restore(1, full);

        // when
        final boolean accepted = buffer.restore(1, List.of(forecast(2, 1)));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(accepted).isFalse();
            softly.assertThat(buffer.size()).isEqualTo(12);
        }
        assertThat(buffer.next(2)).hasSize(1);
    }

    @Test
    void 앞의_관측이_정산되지_않아도_다음_관측의_예보를_평가한다() {
        // given
        PendingEvaluationBuffer buffer = new PendingEvaluationBuffer(12);
        buffer.restore(1, List.of(forecast(1, 1)));
        buffer.restore(1, List.of(forecast(2, 1)));

        // when
        PendingEvaluationBuffer.Entry first = buffer.next(1).getFirst();
        PendingEvaluationBuffer.Entry second = buffer.next(1).getFirst();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(first.key().observationId()).isEqualTo(1);
            softly.assertThat(second.key().observationId()).isEqualTo(2);
        }
    }

    @Test
    void 서로_다른_관측을_섞거나_같은_정류장_예보를_중복해서_넣으면_거부한다() {
        // given
        PendingEvaluationBuffer buffer = new PendingEvaluationBuffer(12);

        // when
        Throwable caughtFailure = catchThrowable(() -> buffer.restore(1, List.of(forecast(1, 1), forecast(2, 2))));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure1 = catchThrowable(() -> buffer.restore(1, List.of(forecast(1, 1), forecast(1, 1))));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(buffer.size()).isZero();
    }

    private static PendingForecast forecast(final long observation, final int target) {
        Instant observed = Instant.parse("2026-10-01T00:00:00Z");
        return new PendingForecast(observation, target, 1, "vehicle", target, observed, observed);
    }
}
