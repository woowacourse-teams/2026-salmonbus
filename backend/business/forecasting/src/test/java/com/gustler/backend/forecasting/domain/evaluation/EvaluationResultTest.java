package com.gustler.backend.forecasting.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Lost;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.NotArrivedYet;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.SeatMissing;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Settled;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Skipped;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EvaluationResultTest {
    @ParameterizedTest
    @MethodSource("completedLabels")
    void 도착_판정의_상태와_근거를_평가_결과로_옮긴다(ArrivalLabel label, EvaluationResult expected) {
        // when
        EvaluationResult result = EvaluationResult.from(label);

        // then
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void 아직_도착하지_않은_판정은_확정_결과로_만들지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> EvaluationResult.from(new NotArrivedYet()));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void 상태와_맞지_않는_근거는_거절한다(ScoringState state, Long observationId, Integer seats) {
        // when
        Throwable caughtFailure = catchThrowable(() -> new EvaluationResult(state, observationId, seats));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 상태나_도착_판정이_없으면_결과를_만들지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> new EvaluationResult(null, null, null));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(NullPointerException.class);

        // when
        Throwable phaseFailure1 = catchThrowable(() -> EvaluationResult.from(null));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(NullPointerException.class);
    }

    private static Stream<Arguments> completedLabels() {
        return Stream.of(
            Arguments.of(new Settled(10L, 0), new EvaluationResult(ScoringState.SETTLED, 10L, 0)),
            Arguments.of(new Settled(10L, 12), new EvaluationResult(ScoringState.SETTLED, 10L, 12)),
            Arguments.of(new SeatMissing(10L), new EvaluationResult(ScoringState.SEAT_MISSING, 10L, null)),
            Arguments.of(new Skipped(), new EvaluationResult(ScoringState.SKIPPED, null, null)),
            Arguments.of(new Lost(), new EvaluationResult(ScoringState.LOST, null, null))
        );
    }

    private static Stream<Arguments> invalidResults() {
        return Stream.of(
            Arguments.of(ScoringState.PENDING, null, null),
            Arguments.of(ScoringState.SETTLED, null, 0),
            Arguments.of(ScoringState.SETTLED, 0L, 0),
            Arguments.of(ScoringState.SETTLED, -1L, 0),
            Arguments.of(ScoringState.SETTLED, 10L, null),
            Arguments.of(ScoringState.SETTLED, 10L, -1),
            Arguments.of(ScoringState.SEAT_MISSING, null, null),
            Arguments.of(ScoringState.SEAT_MISSING, 0L, null),
            Arguments.of(ScoringState.SEAT_MISSING, 10L, 0),
            Arguments.of(ScoringState.SKIPPED, 10L, null),
            Arguments.of(ScoringState.SKIPPED, null, 0),
            Arguments.of(ScoringState.LOST, 10L, null),
            Arguments.of(ScoringState.LOST, null, 0)
        );
    }
}
