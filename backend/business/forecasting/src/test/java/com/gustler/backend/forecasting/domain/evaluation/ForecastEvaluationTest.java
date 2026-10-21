package com.gustler.backend.forecasting.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.NotArrivedYet;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.SeatMissing;
import java.time.Instant;
import java.util.stream.Stream;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ForecastEvaluationTest {
    private static final long OBSERVATION_ID = 10L;
    private static final int TARGET_STOP_ORDER = 9;
    private static final Instant SCORED_AT = Instant.parse("2026-09-23T02:00:00Z");

    @Test
    void 새_평가는_원_관측과_대상_정류장을_기억하고_결과를_기다린다() {
        // when
        ForecastEvaluation evaluation = ForecastEvaluation.pending(OBSERVATION_ID, TARGET_STOP_ORDER);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(evaluation.vehicleObservationId()).isEqualTo(OBSERVATION_ID);
            softly.assertThat(evaluation.targetStopOrder()).isEqualTo(TARGET_STOP_ORDER);
            softly.assertThat(evaluation.state()).isEqualTo(ScoringState.PENDING);
        }
        assertThat(evaluation.result()).isNull();
        assertThat(evaluation.scoredAt()).isNull();
    }

    @ParameterizedTest
    @MethodSource("terminalResults")
    void 평가를_확정하면_결과와_시각이_함께_남는다(EvaluationResult result) {
        // given
        ForecastEvaluation evaluation = ForecastEvaluation.pending(OBSERVATION_ID, TARGET_STOP_ORDER);

        // when
        evaluation.complete(result, SCORED_AT);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(evaluation.state()).isEqualTo(result.state());
            softly.assertThat(evaluation.result()).isEqualTo(result);
            softly.assertThat(evaluation.scoredAt()).isEqualTo(SCORED_AT);
        }
    }

    @ParameterizedTest
    @MethodSource("terminalResults")
    void 확정한_평가는_다시_변경할_수_없다(EvaluationResult original) {
        // given
        ForecastEvaluation evaluation = ForecastEvaluation.pending(OBSERVATION_ID, TARGET_STOP_ORDER);
        evaluation.complete(original, SCORED_AT);
        EvaluationResult replacement = new EvaluationResult(ScoringState.SETTLED, 30L, 5);

        // when
        assertThatIllegalStateException()
            .isThrownBy(() -> evaluation.complete(replacement, SCORED_AT.plusSeconds(10)));
        assertThatIllegalStateException().isThrownBy(() -> evaluation.complete(original, SCORED_AT));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(evaluation.result()).isEqualTo(original);
            softly.assertThat(evaluation.scoredAt()).isEqualTo(SCORED_AT);
        }
    }

    @Test
    void 결과나_시각이_누락된_확정은_대기_상태를_변경하지_않는다() {
        // given
        ForecastEvaluation evaluation = ForecastEvaluation.pending(OBSERVATION_ID, TARGET_STOP_ORDER);
        EvaluationResult result = new EvaluationResult(ScoringState.LOST, null, null);

        // when
        assertThatNullPointerException().isThrownBy(() -> evaluation.complete(null, SCORED_AT));
        assertThatNullPointerException().isThrownBy(() -> evaluation.complete(result, null));

        // then
        assertThat(evaluation.state()).isEqualTo(ScoringState.PENDING);
        assertThat(evaluation.result()).isNull();
        assertThat(evaluation.scoredAt()).isNull();
        evaluation.complete(result, SCORED_AT);
        assertThat(evaluation.state()).isEqualTo(ScoringState.LOST);
    }

    @Test
    void 도착_판정으로_생성해도_동일한_확정_규칙을_적용한다() {
        // when
        ForecastEvaluation evaluation = ForecastEvaluation.completed(
            OBSERVATION_ID, TARGET_STOP_ORDER, new SeatMissing(20L), SCORED_AT);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(evaluation.state()).isEqualTo(ScoringState.SEAT_MISSING);
            softly.assertThat(evaluation.result()).isEqualTo(
                new EvaluationResult(ScoringState.SEAT_MISSING, 20L, null));
            softly.assertThat(evaluation.scoredAt()).isEqualTo(SCORED_AT);
        }

        // when
        Throwable phaseFailure1 = catchThrowable(() -> evaluation.complete(evaluation.result(), SCORED_AT));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 도착_대기_판정으로_완료된_평가를_만들지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> ForecastEvaluation.completed(
            OBSERVATION_ID, TARGET_STOP_ORDER, new NotArrivedYet(), SCORED_AT));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidIdentities")
    void 유효한_관측과_대상_정류장이_필요하다(final long observationId, final int targetStopOrder) {
        // when
        Throwable caughtFailure = catchThrowable(() -> ForecastEvaluation.pending(observationId, targetStopOrder));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<EvaluationResult> terminalResults() {
        return Stream.of(
            new EvaluationResult(ScoringState.SETTLED, 20L, 0),
            new EvaluationResult(ScoringState.SEAT_MISSING, 20L, null),
            new EvaluationResult(ScoringState.SKIPPED, null, null),
            new EvaluationResult(ScoringState.LOST, null, null)
        );
    }

    private static Stream<Arguments> invalidIdentities() {
        return Stream.of(
            Arguments.of(0L, TARGET_STOP_ORDER),
            Arguments.of(-1L, TARGET_STOP_ORDER),
            Arguments.of(OBSERVATION_ID, 0),
            Arguments.of(OBSERVATION_ID, -1)
        );
    }
}
