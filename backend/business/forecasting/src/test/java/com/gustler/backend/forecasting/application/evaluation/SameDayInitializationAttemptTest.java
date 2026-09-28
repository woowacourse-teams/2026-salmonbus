package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SameDayInitializationAttemptTest {
    private final AtomicLong nanos = new AtomicLong();
    private final SameDayInitializationAttempt attempt = new SameDayInitializationAttempt(nanos::get);
    private void advance(long ms) { nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms)); }

    @Test
    void 잠금_획득_실패는_원본_조회_미실행으로_기록한다() {
        var failure = new IllegalStateException(new SQLException("fixture", "55P03"));
        assertThatThrownBy(() -> attempt.run(SameDayInitializationAttempt.Stage.LOCK, () -> {
            advance(100);
            throw failure;
        })).isSameAs(failure);
        attempt.failed(failure);
        assertThat(attempt.failedStage()).isEqualTo("LOCK");
        assertThat(attempt.sqlState()).isEqualTo("55P03");
        assertThat(attempt.lockAcquireMs()).isEqualTo(100L);
        assertThat(attempt.sourceAttempted()).isFalse();
        assertThat(attempt.sourceQueryMs()).isNull();
    }

    @Test
    void 원본_조회_시간초과는_실패_구간과_SQLSTATE를_기록한다() {
        var failure = new IllegalStateException(new SQLException("fixture", "57014"));
        assertThatThrownBy(() -> attempt.run(SameDayInitializationAttempt.Stage.SOURCE, () -> {
            advance(500);
            throw failure;
        })).isSameAs(failure);
        attempt.failed(failure);
        assertThat(attempt.failedStage()).isEqualTo("SOURCE");
        assertThat(attempt.sqlState()).isEqualTo("57014");
        assertThat(attempt.sourceAttempted()).isTrue();
        assertThat(attempt.sourceQueryMs()).isEqualTo(500L);
        assertThat(attempt.totalMs()).isEqualTo(500L);
    }

    @Test
    void 전체_시간은_커밋까지_포함하고_구간별_시간은_단조_시계로_잰다() {
        attempt.run(SameDayInitializationAttempt.Stage.LOCK, () -> advance(10));
        attempt.run(SameDayInitializationAttempt.Stage.SOURCE, () -> advance(150));
        attempt.awaitingCommit();
        advance(20);
        attempt.completed(true);
        assertThat(attempt.lockAcquireMs()).isEqualTo(10L);
        assertThat(attempt.sourceQueryMs()).isEqualTo(150L);
        assertThat(attempt.totalMs()).isEqualTo(180L);
        assertThat(attempt.failedStage()).isEqualTo("-");
    }
}
