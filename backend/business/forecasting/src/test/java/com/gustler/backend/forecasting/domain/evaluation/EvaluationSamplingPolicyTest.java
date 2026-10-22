package com.gustler.backend.forecasting.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class EvaluationSamplingPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");
    private final EvaluationSamplingPolicy policy = new EvaluationSamplingPolicy(Duration.ofSeconds(60));

    @Test
    void 처음_본_차량과_재시작_후_첫_관측은_모두_상세_저장한다() {
        // given
        EvaluationSamplingPoint current = point(1, 1, "vehicle", 3, "UP", NOW);

        // when
        final boolean actual = policy.includeAll(null, current);

        // then
        assertThat(actual).isTrue();
    }

    @Test
    void 같은_정류장의_60초_미만_관측은_한_정류장_앞만_상세_저장한다() {
        // given
        EvaluationSamplingPoint previous = point(1, 1, "vehicle", 3, "UP", NOW);
        EvaluationSamplingPoint current = point(1, 1, "vehicle", 3, "UP", NOW.plusMillis(59_999));

        // when
        final boolean all = policy.includeAll(previous, current);

        // then
        assertThat(all).isFalse();
        assertThat(policy.includesDistance(all, 1)).isTrue();
        assertThat(policy.includesDistance(all, 2)).isFalse();
        assertThat(policy.includesDistance(all, 12)).isFalse();
    }

    @Test
    void 마지막_저장으로부터_정확히_60초가_지나면_모든_거리를_상세_저장한다() {
        // given
        EvaluationSamplingPoint previous = point(1, 1, "vehicle", 3, "UP", NOW);
        EvaluationSamplingPoint current = point(1, 1, "vehicle", 3, "UP", NOW.plusSeconds(60));

        // when
        final boolean all = policy.includeAll(previous, current);

        // then
        assertThat(all).isTrue();
        assertThat(policy.includesDistance(all, 12)).isTrue();
    }

    @Test
    void 시간_구간_경계를_넘어도_60초가_안_지났으면_상세를_생략한다() {
        // given
        EvaluationSamplingPoint previous = point(1, 1, "vehicle", 3, "UP", NOW.plusSeconds(59));
        EvaluationSamplingPoint current = point(1, 1, "vehicle", 3, "UP", NOW.plusSeconds(61));

        // when
        final boolean actual = policy.includeAll(previous, current);

        // then
        assertThat(actual).isFalse();
    }

    @Test
    void 정류장이나_방향이_바뀌면_60초_이전에도_상세_저장한다() {
        // given
        EvaluationSamplingPoint previous = point(1, 1, "vehicle", 3, "UP", NOW);

        // when
        final boolean moved = policy.includeAll(previous, point(1, 1, "vehicle", 4, "UP", NOW.plusSeconds(15)));
        final boolean turned = policy.includeAll(previous, point(1, 1, "vehicle", 3, "DOWN", NOW.plusSeconds(15)));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(moved).isTrue();
            softly.assertThat(turned).isTrue();
        }
    }

    @Test
    void 노선_버전과_모델과_차량이_바뀌면_새_표본을_남긴다() {
        // given
        EvaluationSamplingPoint previous = point(1, 1, "vehicle", 3, "UP", NOW);

        // when
        final boolean route = policy.includeAll(previous, point(2, 1, "vehicle", 3, "UP", NOW.plusSeconds(15)));
        final boolean model = policy.includeAll(previous, point(1, 2, "vehicle", 3, "UP", NOW.plusSeconds(15)));
        final boolean vehicle = policy.includeAll(previous, point(1, 1, "other", 3, "UP", NOW.plusSeconds(15)));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(route).isTrue();
            softly.assertThat(model).isTrue();
            softly.assertThat(vehicle).isTrue();
        }
    }

    @Test
    void 과거_관측의_재처리는_생략하지_않는다() {
        // given
        EvaluationSamplingPoint previous = point(1, 1, "vehicle", 3, "UP", NOW);

        // when
        final boolean actual = policy.includeAll(previous, point(1, 1, "vehicle", 3, "UP", NOW.minusSeconds(15)));

        // then
        assertThat(actual).isTrue();
    }

    @Test
    void 양수가_아닌_간격은_거부한다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> new EvaluationSamplingPolicy(Duration.ZERO));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure1 = catchThrowable(() -> new EvaluationSamplingPolicy(Duration.ofSeconds(-1)));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }

    private EvaluationSamplingPoint point(
        final long route,
        final long model,
        String vehicle,
        final int stop,
        String direction,
        Instant at
    ) {
        return new EvaluationSamplingPoint(route, model, vehicle, stop, direction, at);
    }
}
