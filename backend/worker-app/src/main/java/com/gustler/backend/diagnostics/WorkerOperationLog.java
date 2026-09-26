package com.gustler.backend.diagnostics;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 진단을 위한 DB 조회는 하지 않는다. SQL 본문·파라미터·예외 메시지·차량 식별자는 기록하지 않는다. */
public final class WorkerOperationLog {
    private static final Recorder RECORDER = new Recorder(System::nanoTime);

    private WorkerOperationLog() { }

    public static <T> T measure(String operation, Object route, Supplier<T> action) {
        return RECORDER.measure(operation, route, action);
    }

    public static void run(String operation, Object route, Runnable action) {
        measure(operation, route, () -> { action.run(); return null; });
    }

    public static void warn(String operation, Object route, String reason) {
        RECORDER.warn(operation, route, reason);
    }

    public static void recovered(String operation, Object route) {
        RECORDER.recovered(operation, route);
    }

    static final class Recorder {
        private static final Logger log = LoggerFactory.getLogger(WorkerOperationLog.class);
        private static final long SLOW_NANOS = TimeUnit.SECONDS.toNanos(1);
        private static final long WARNING_NANOS = TimeUnit.MINUTES.toNanos(1);
        private final LongSupplier nanoTime;
        private final ThreadLocal<Set<Throwable>> reported = new ThreadLocal<>();
        private final Map<String, Long> warnings = new LinkedHashMap<>();

        Recorder(LongSupplier nanoTime) { this.nanoTime = nanoTime; }

        <T> T measure(String operation, Object route, Supplier<T> action) {
            boolean outermost = reported.get() == null;
            if (outermost) { reported.set(Collections.newSetFromMap(new IdentityHashMap<>())); }
            long start = nanoTime.getAsLong();
            try {
                T result = action.get();
                long elapsed = nanoTime.getAsLong() - start;
                if (elapsed >= SLOW_NANOS) {
                    int rows = result instanceof Collection<?> collection ? collection.size() : -1;
                    slow(operation, route, elapsed, rows);
                }
                return result;
            } catch (RuntimeException failure) {
                Set<Throwable> chain = Collections.newSetFromMap(new IdentityHashMap<>());
                Throwable root = failure;
                String sqlState = "-";
                for (Throwable cause = failure; cause != null && chain.add(cause); cause = cause.getCause()) {
                    root = cause;
                    if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                        sqlState = sql.getSQLState();
                    }
                }
                if (Collections.disjoint(reported.get(), chain)) {
                    log.error("event=worker_operation status=FAILED operation={} route={} durationMs={} sqlState={} exceptionType={} rootCause={}",
                        operation, route, millis(nanoTime.getAsLong() - start), sqlState,
                        failure.getClass().getSimpleName(), root.getClass().getSimpleName());
                }
                reported.get().addAll(chain);
                throw failure;
            } finally {
                if (outermost) { reported.remove(); }
            }
        }

        private void slow(String operation, Object route, long elapsed, int rows) {
            if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
                // 메서드 반환을 commit 성공으로 기록하지 않는다. 실패한 transaction의 느린 조회도 구분한다.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        String outcome = switch (status) {
                            case STATUS_COMMITTED -> "COMMITTED";
                            case STATUS_ROLLED_BACK -> "ROLLED_BACK";
                            default -> "UNKNOWN";
                        };
                        writeSlow(operation, route, elapsed, rows, outcome);
                    }
                });
            } else {
                // 반환한 호출의 시간이며 외부 transaction의 존재나 영속화를 추측하지 않는다.
                writeSlow(operation, route, elapsed, rows, "NOT_OBSERVED");
            }
        }

        private void writeSlow(String operation, Object route, long elapsed, int rows, String outcome) {
            log.warn("event=worker_operation status=SLOW operation={} route={} durationMs={} returnedRows={} transactionOutcome={}",
                operation, route, millis(elapsed), rows, outcome);
        }

        synchronized void warn(String operation, Object route, String reason) {
            String key = operation + ":" + route;
            long now = nanoTime.getAsLong();
            Long last = warnings.get(key);
            if (last != null && now - last < WARNING_NANOS) { return; }
            if (!warnings.containsKey(key) && warnings.size() >= 256) {
                warnings.remove(warnings.keySet().iterator().next());
            }
            warnings.put(key, now);
            log.warn("event=worker_operation status=DEFERRED operation={} route={} reason={}", operation, route, reason);
        }

        synchronized void recovered(String operation, Object route) {
            warnings.remove(operation + ":" + route);
        }

        private static long millis(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }
    }
}
