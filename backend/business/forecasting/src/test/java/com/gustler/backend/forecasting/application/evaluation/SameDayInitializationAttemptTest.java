package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.application.evaluation.SameDayInitializationAttempt.Stage;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class SameDayInitializationAttemptTest {
    private final AtomicLong nanos = new AtomicLong();
    private final SameDayInitializationAttempt attempt = new SameDayInitializationAttempt(nanos::get);

    private void advance(final long ms) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    @Test
    void 잠금_획득_실패는_원본_조회_미실행으로_기록한다() {
        // when
        IllegalStateException failure = new IllegalStateException(new SQLException("fixture", "55P03"));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> attempt.run(Stage.LOCK, () -> {
            advance(100);
            throw failure;
        }));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isSameAs(failure);
        attempt.failed(failure);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(attempt.failedStage()).isEqualTo("LOCK");
            softly.assertThat(attempt.sqlState()).isEqualTo("55P03");
            softly.assertThat(attempt.lockAcquireMs()).isEqualTo(100L);
            softly.assertThat(attempt.sourceAttempted()).isFalse();
        }
        assertThat(attempt.sourceQueryMs()).isNull();
    }

    @Test
    void 원본_조회_시간초과는_실패_구간과_SQLSTATE를_기록한다() {
        // when
        IllegalStateException failure = new IllegalStateException(new SQLException("fixture", "57014"));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> attempt.run(Stage.SOURCE, () -> {
            advance(500);
            throw failure;
        }));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isSameAs(failure);
        attempt.failed(failure);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(attempt.failedStage()).isEqualTo("SOURCE");
            softly.assertThat(attempt.sqlState()).isEqualTo("57014");
            softly.assertThat(attempt.sourceAttempted()).isTrue();
            softly.assertThat(attempt.sourceQueryMs()).isEqualTo(500L);
            softly.assertThat(attempt.totalMs()).isEqualTo(500L);
        }
    }

    @Test
    void 전체_시간은_커밋까지_포함하고_구간별_시간은_단조_시계로_잰다() {
        // when
        attempt.run(Stage.LOCK, () -> advance(10));
        attempt.run(Stage.SOURCE, () -> advance(150));
        attempt.awaitingCommit();
        advance(20);
        attempt.completed(true);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(attempt.lockAcquireMs()).isEqualTo(10L);
            softly.assertThat(attempt.sourceQueryMs()).isEqualTo(150L);
            softly.assertThat(attempt.totalMs()).isEqualTo(180L);
            softly.assertThat(attempt.failedStage()).isEqualTo("-");
        }
    }
}
