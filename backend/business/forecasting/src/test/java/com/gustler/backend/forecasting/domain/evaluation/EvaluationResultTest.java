package com.gustler.backend.forecasting.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
        assertThatIllegalArgumentException()
            .isThrownBy(() -> EvaluationResult.from(new ArrivalLabel.NotArrivedYet()));
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void 상태와_맞지_않는_근거는_거절한다(ScoringState state, Long observationId, Integer seats) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new EvaluationResult(state, observationId, seats));
    }

    @Test
    void 상태나_도착_판정이_없으면_결과를_만들지_않는다() {
        assertThatNullPointerException().isThrownBy(() -> new EvaluationResult(null, null, null));
        assertThatNullPointerException().isThrownBy(() -> EvaluationResult.from(null));
    }

    private static Stream<Arguments> completedLabels() {
        return Stream.of(
            Arguments.of(new ArrivalLabel.Settled(10L, 0), new EvaluationResult(ScoringState.SETTLED, 10L, 0)),
            Arguments.of(new ArrivalLabel.Settled(10L, 12), new EvaluationResult(ScoringState.SETTLED, 10L, 12)),
            Arguments.of(new ArrivalLabel.SeatMissing(10L), new EvaluationResult(ScoringState.SEAT_MISSING, 10L, null)),
            Arguments.of(new ArrivalLabel.Skipped(), new EvaluationResult(ScoringState.SKIPPED, null, null)),
            Arguments.of(new ArrivalLabel.Lost(), new EvaluationResult(ScoringState.LOST, null, null))
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
