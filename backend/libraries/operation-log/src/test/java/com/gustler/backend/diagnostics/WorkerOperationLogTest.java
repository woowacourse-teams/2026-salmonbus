package com.gustler.backend.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.diagnostics.WorkerOperationLog.Listener;
import com.gustler.backend.diagnostics.WorkerOperationLog.Recorder;
import java.sql.SQLException;
import java.util.ArrayList;
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
    private final Recorder recorder = new Recorder(nanos::get);
    private final Logger logger = (Logger) LoggerFactory.getLogger(WorkerOperationLog.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        WorkerOperationLog.setListener(null);
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void 빠른_정상_조회는_로그를_남기지_않고_결과를_그대로_반환한다() {
        // when
        List<Integer> result = List.of(1, 2);

        // then
        assertThat(recorder.measure("query", 1L, () -> result)).isSameAs(result);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 일초부터_소요시간과_반환행수를_기록하고_값은_숨긴다() {
        // when
        recorder.measure("query", 1L, () -> {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
            return List.of("private-vehicle", "private-value");
        });

        // then
        assertThat(messages()).singleElement().asString()
            .contains("status=SLOW", "durationMs=1000", "returnedRows=2", "transactionOutcome=NOT_OBSERVED")
            .doesNotContain("private");
    }

    @Test
    void 중첩_실패는_가장_안쪽_작업과_SQLState만_한번_기록한다() {
        // when
        DataAccessResourceFailureException failure =
            new DataAccessResourceFailureException("private query", new SQLException("private vehicle", "08006"));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> recorder.measure("forecast", 1L,
            () -> recorder.measure("same_day_seed_source", 1L, () -> {
                throw failure;
            })));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isSameAs(failure);
        assertThat(messages()).singleElement().asString()
            .contains("operation=same_day_seed_source", "sqlState=08006", "rootCause=SQLException")
            .doesNotContain("private", "operation=forecast");
        assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void 같은_예외라도_다음_실행의_실패는_다시_기록한다() {
        // when
        IllegalStateException failure = new IllegalStateException("private");
        for (int i = 0; i < 2; i++) {

            // then

            // when
            Throwable phaseFailure1 = catchThrowable(() -> recorder.measure("query", 1L, () -> {
                throw failure;
            }));

            // then
            assertThat(phaseFailure1)
                .isNotNull().isSameAs(failure);
        }
        assertThat(messages()).hasSize(2);
    }

    @Test
    void 다른_원인으로_실패하면_같은_실행에서도_숨기지_않는다() {
        // when
        recorder.measure("outer", 1L, () -> {
            for (int i = 0; i < 2; i++) {
                try {
                    recorder.measure("inner", 1L, () -> {
                        throw new IllegalStateException();
                    });
                }
                catch (IllegalStateException ignored) {
                    // 첫 실패 뒤에도 다음 독립 실패를 실행해 중복 억제 범위를 검사한다.
                    continue;
                }
            }
            return null;
        });

        // then
        assertThat(messages()).hasSize(2);
    }

    @Test
    void transaction이_완료되기_전에는_느린_조회_성공을_기록하지_않는다() {
        // when
        TestTransactionManager manager =
            new TestTransactionManager(false);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            slowQuery();

            // then
            assertThat(logs.list).isEmpty();
        });
        assertThat(messages()).singleElement().asString().contains("transactionOutcome=COMMITTED");
    }

    @Test
    void commit_실패를_성공으로_기록하지_않고_호출경계에서_SQLState를_남긴다() {
        // when
        TestTransactionManager manager =
            new TestTransactionManager(true);

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> recorder.measure("forecast_write_and_commit", 1L, () ->
            new TransactionTemplate(manager).execute(status -> {
                slowQuery();
                return null;
            })));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(TransactionSystemException.class);
        assertThat(messages()).anyMatch(x -> x.contains("status=FAILED") && x.contains("sqlState=08006"));
        assertThat(messages()).noneMatch(x -> x.contains("transactionOutcome=COMMITTED"));
    }

    @Test
    void rollback된_느린_조회는_rollback으로_구분한다() {
        // when
        new TransactionTemplate(new TestTransactionManager(false)).executeWithoutResult(status -> {
            slowQuery();
            status.setRollbackOnly();
        });

        // then
        assertThat(messages()).singleElement().asString().contains("transactionOutcome=ROLLED_BACK");
    }

    @Test
    void 반복_경고는_분당_한번이고_다른_노선은_즉시_기록한다() {
        // when
        recorder.warn("quota", 1L, "LIMIT");
        recorder.warn("quota", 1L, "LIMIT");
        recorder.warn("quota", 2L, "LIMIT");

        // then
        assertThat(messages()).hasSize(2);
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(1));
        recorder.warn("quota", 1L, "LIMIT");
        assertThat(messages()).hasSize(3);
    }

    @Test
    void 복구_후_재발한_경고는_일분_이내여도_즉시_기록한다() {
        // when
        recorder.warn("quota", 1L, "LIMIT");
        recorder.recovered("quota", 1L);
        recorder.warn("quota", 1L, "LIMIT");

        // then
        assertThat(messages()).hasSize(2);
    }

    @Test
    void 같은_노선이라도_경고_사유가_다르면_즉시_기록한다() {
        // when
        recorder.warn("quota", 1L, "DAILY_LIMIT");
        recorder.warn("quota", 1L, "NEXT_DAY_LIMIT");
        recorder.warn("quota", 1L, "DAILY_LIMIT");
        recorder.warn("quota", 1L, "NEXT_DAY_LIMIT");

        // then
        assertThat(messages()).hasSize(2);
        assertThat(messages().get(0)).contains("reason=DAILY_LIMIT");
        assertThat(messages().get(1)).contains("reason=NEXT_DAY_LIMIT");
    }

    @Test
    void 복구하면_해당_작업과_노선의_모든_사유만_초기화한다() {
        // given
        recorder.warn("quota", 1L, "DAILY_LIMIT");
        recorder.warn("quota", 1L, "NEXT_DAY_LIMIT");
        recorder.warn("quota", 2L, "DAILY_LIMIT");
        recorder.warn("other", 1L, "DAILY_LIMIT");
        logs.list.clear();

        // when
        recorder.recovered("quota", 1L);
        recorder.warn("quota", 1L, "DAILY_LIMIT");
        recorder.warn("quota", 1L, "NEXT_DAY_LIMIT");
        recorder.warn("quota", 2L, "DAILY_LIMIT");
        recorder.warn("other", 1L, "DAILY_LIMIT");

        // then
        assertThat(messages()).hasSize(2).allSatisfy(message ->
            assertThat(message).contains("operation=quota", "route=1"));
    }

    @Test
    void 정상과_실패_실행을_각각_계측하고_계측_실패는_업무_결과를_바꾸지_않는다() {
        // when
        ArrayList<Boolean> outcomes = new ArrayList<>();
        WorkerOperationLog.setListener((operation, route, elapsed, failed) -> {
            outcomes.add(failed);
            throw new IllegalStateException("telemetry failure");
        });

        // then
        assertThat(recorder.measure("test", 1L, () -> 42)).isEqualTo(42);
        IllegalArgumentException failure = new IllegalArgumentException("business failure");

        // when
        Throwable phaseFailure1 = catchThrowable(() -> recorder.measure("test", 1L, () -> {
            throw failure;
        }));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isSameAs(failure);
        assertThat(outcomes).containsExactly(false, true);
    }

    @Test
    void 노선_표시명은_기존_필드_뒤에만_추가한다() {
        // when
        WorkerOperationLog.setListener(new Listener() {
            @Override
            public void completed(
                String operation,
                Object route,
                final long elapsed,
                final boolean failed
            ) {
            }

            @Override
            public String context(String operation, Object route) {
                return " routeName=\"3330\"";
            }
        });
        slowQuery();

        // then
        assertThat(messages()).singleElement().asString()
            .contains("operation=query route=1 durationMs=1000")
            .endsWith("transactionOutcome=NOT_OBSERVED routeName=\"3330\"");
    }

    @Test
    void 수집_최신성은_외부_트랜잭션_커밋_뒤에만_갱신한다() {
        // when
        AtomicLong calls = new AtomicLong();
        WorkerOperationLog.setListener(new Listener() {
            @Override
            public void completed(
                String operation,
                Object route,
                final long elapsed,
                final boolean failed
            ) {
            }

            @Override
            public void collectionCommitted(String route, final long observedAt, final int rows) {
                calls.incrementAndGet();
            }
        });
        new TransactionTemplate(new TestTransactionManager(false)).executeWithoutResult(status -> {
            WorkerOperationLog.collectionCommitted("234000886", 123, 0);

            // then
            assertThat(calls.get()).isZero();
        });
        assertThat(calls.get()).isEqualTo(1);
        new TransactionTemplate(new TestTransactionManager(false)).executeWithoutResult(status -> {
            WorkerOperationLog.collectionCommitted("234000886", 124, 1);
            status.setRollbackOnly();
        });
        assertThat(calls.get()).isEqualTo(1);

        // when
        Throwable phaseFailure1 = catchThrowable(() ->
            new TransactionTemplate(new TestTransactionManager(true)).executeWithoutResult(status ->
                WorkerOperationLog.collectionCommitted("234000886", 125, 1)));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(
                    TransactionSystemException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    private void slowQuery() {
        recorder.measure("query", 1L, () -> {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
            return List.of(1);
        });
    }

    private List<String> messages() {
        return logs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final boolean failCommit;

        TestTransactionManager(final boolean failCommit) {
            this.failCommit = failCommit;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("commit", new SQLException("private", "08006"));
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
