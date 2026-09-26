package com.gustler.backend.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class WorkerOperationLogTest {
    private final AtomicLong nanos = new AtomicLong();
    private final WorkerOperationLog.Recorder recorder = new WorkerOperationLog.Recorder(nanos::get);
    private final Logger logger = (Logger) LoggerFactory.getLogger(WorkerOperationLog.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach void attach() { logs.start(); logger.addAppender(logs); }
    @AfterEach void detach() { logger.detachAppender(logs); logs.stop(); }

    @Test
    void 빠른_정상_조회는_로그를_남기지_않고_결과를_그대로_반환한다() {
        var result = List.of(1, 2);
        assertThat(recorder.measure("query", 1L, () -> result)).isSameAs(result);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 일초부터_소요시간과_반환행수를_기록하고_값은_숨긴다() {
        recorder.measure("query", 1L, () -> {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
            return List.of("private-vehicle", "private-value");
        });
        assertThat(messages()).singleElement().asString()
            .contains("status=SLOW", "durationMs=1000", "returnedRows=2", "transactionOutcome=NOT_OBSERVED")
            .doesNotContain("private");
    }

    @Test
    void 중첩_실패는_가장_안쪽_작업과_SQLState만_한번_기록한다() {
        var failure = new DataAccessResourceFailureException("private query", new SQLException("private vehicle", "08006"));
        assertThatThrownBy(() -> recorder.measure("forecast", 1L,
            () -> recorder.measure("same_day_seed_source", 1L, () -> { throw failure; }))).isSameAs(failure);
        assertThat(messages()).singleElement().asString()
            .contains("operation=same_day_seed_source", "sqlState=08006", "rootCause=SQLException")
            .doesNotContain("private", "operation=forecast");
        assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void 같은_예외라도_다음_실행의_실패는_다시_기록한다() {
        var failure = new IllegalStateException("private");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> recorder.measure("query", 1L, () -> { throw failure; })).isSameAs(failure);
        }
        assertThat(messages()).hasSize(2);
    }

    @Test
    void 다른_원인으로_실패하면_같은_실행에서도_숨기지_않는다() {
        recorder.measure("outer", 1L, () -> {
            for (int i = 0; i < 2; i++) {
                try { recorder.measure("inner", 1L, () -> { throw new IllegalStateException(); }); }
                catch (IllegalStateException ignored) { }
            }
            return null;
        });
        assertThat(messages()).hasSize(2);
    }

    @Test
    void transaction이_완료되기_전에는_느린_조회_성공을_기록하지_않는다() {
        var manager = new TestTransactionManager(false);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            slowQuery();
            assertThat(logs.list).isEmpty();
        });
        assertThat(messages()).singleElement().asString().contains("transactionOutcome=COMMITTED");
    }

    @Test
    void commit_실패를_성공으로_기록하지_않고_호출경계에서_SQLState를_남긴다() {
        var manager = new TestTransactionManager(true);
        assertThatThrownBy(() -> recorder.measure("forecast_write_and_commit", 1L, () ->
            new TransactionTemplate(manager).execute(status -> { slowQuery(); return null; })))
            .isInstanceOf(TransactionSystemException.class);
        assertThat(messages()).anyMatch(x -> x.contains("status=FAILED") && x.contains("sqlState=08006"));
        assertThat(messages()).noneMatch(x -> x.contains("transactionOutcome=COMMITTED"));
    }

    @Test
    void rollback된_느린_조회는_rollback으로_구분한다() {
        new TransactionTemplate(new TestTransactionManager(false)).executeWithoutResult(status -> {
            slowQuery();
            status.setRollbackOnly();
        });
        assertThat(messages()).singleElement().asString().contains("transactionOutcome=ROLLED_BACK");
    }

    @Test
    void 반복_경고는_분당_한번이고_다른_노선은_즉시_기록한다() {
        recorder.warn("quota", 1L, "LIMIT");
        recorder.warn("quota", 1L, "LIMIT");
        recorder.warn("quota", 2L, "LIMIT");
        assertThat(messages()).hasSize(2);
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(1));
        recorder.warn("quota", 1L, "LIMIT");
        assertThat(messages()).hasSize(3);
    }

    @Test
    void 복구_후_재발한_경고는_일분_이내여도_즉시_기록한다() {
        recorder.warn("quota", 1L, "LIMIT");
        recorder.recovered("quota", 1L);
        recorder.warn("quota", 1L, "LIMIT");
        assertThat(messages()).hasSize(2);
    }

    private void slowQuery() {
        recorder.measure("query", 1L, () -> { nanos.addAndGet(TimeUnit.SECONDS.toNanos(1)); return List.of(1); });
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final boolean failCommit;
        TestTransactionManager(boolean failCommit) { this.failCommit = failCommit; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) { throw new TransactionSystemException("commit", new SQLException("private", "08006")); }
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
