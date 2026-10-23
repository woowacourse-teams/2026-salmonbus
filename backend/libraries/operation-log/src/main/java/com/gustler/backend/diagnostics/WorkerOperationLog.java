package com.gustler.backend.diagnostics;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 진단을 위한 DB 조회는 하지 않는다. SQL 본문·파라미터·예외 메시지·차량 식별자는 기록하지 않는다. */
public final class WorkerOperationLog {
    private static final Recorder RECORDER = new Recorder(System::nanoTime);

    public interface Listener {
        void completed(
            String operation,
            Object route,
            long nanos,
            boolean failed
        );

        default void collectionAttempted(String sourceRoute, final long epochSecond) {
        }

        default void collectionCommitted(String sourceRoute, final long observedEpochSecond, final int usableRows) {
        }

        default String context(String operation, Object route) {
            return "";
        }
    }
    private static final Listener NONE = (operation, route, nanos, failed) -> {
    };
    private static volatile Listener listener = NONE;

    private WorkerOperationLog() {
    }

    /** 수집 계측 수신자를 교체한다. null이면 수신자를 해제한다. */
    public static void setListener(Listener value) {
        if (value == null) {
            listener = NONE;
            return;
        }
        listener = value;
    }

    public static void clearListener(Listener value) {
        if (listener == value) {
            listener = NONE;
        }
    }

    private static String context(String operation, Object route) {
        try {
            return listener.context(operation, route);
        }
        catch (RuntimeException ignored) {
            return "";
        }
    }

    public static String routeContext(String operation, Object route) {
        return context(operation, route);
    }

    public static void collectionAttempted(String sourceRoute, final long epochSecond) {
        try {
            listener.collectionAttempted(sourceRoute, epochSecond);
        }
        catch (RuntimeException ignored) {
            // 진단 실패가 수집 시도를 막지 않는다.
            return;
        }
    }

    /** 저장 메서드 반환 후 호출한다. 외부 트랜잭션이 있으면 커밋까지 기다린다. */
    public static void collectionCommitted(String sourceRoute, final long observedEpochSecond, final int usableRows) {
        Runnable record = () -> {
            try {
                listener.collectionCommitted(sourceRoute, observedEpochSecond, usableRows);
            }
            catch (RuntimeException ignored) {
                // 진단 실패가 수집 결과를 바꾸지 않는다.
                return;
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    record.run();
                }
            });
            return;
        }
        record.run();
    }

    public static <T> T measure(String operation, Object route, Supplier<T> action) {
        return RECORDER.measure(operation, route, action);
    }

    public static void run(String operation, Object route, Runnable action) {
        measure(operation, route, () -> {
            action.run();
            return null;
        });
    }

    public static void warn(String operation, Object route, String reason) {
        RECORDER.warn(operation, route, reason);
    }

    public static void recovered(String operation, Object route) {
        RECORDER.recovered(operation, route);
    }

    static final class Recorder {
        private static final Logger LOGGER = LoggerFactory.getLogger(WorkerOperationLog.class);
        private static final long SLOW_NANOS = TimeUnit.SECONDS.toNanos(1);
        private static final long WARNING_NANOS = TimeUnit.MINUTES.toNanos(1);
        private final LongSupplier nanoTime;
        private final ThreadLocal<Set<Throwable>> reported = new ThreadLocal<>();
        private final Map<WarningKey, Long> warnings = new LinkedHashMap<>();

        Recorder(LongSupplier nanoTime) {
            this.nanoTime = nanoTime;
        }

        <T> T measure(String operation, Object route, Supplier<T> action) {
            final boolean outermost = reported.get() == null;
            if (outermost) {
                reported.set(Collections.newSetFromMap(new IdentityHashMap<>()));
            }
            final long startedAtNanos = nanoTime.getAsLong();
            boolean failed = false;
            try {
                T result = action.get();
                final long elapsedNanos = nanoTime.getAsLong() - startedAtNanos;
                if (elapsedNanos >= SLOW_NANOS) {
                    final int rowCount = returnedRowCount(result);
                    slow(operation, route, elapsedNanos, rowCount);
                }
                return result;
            } catch (RuntimeException failure) {
                failed = true;
                Set<Throwable> chain = Collections.newSetFromMap(new IdentityHashMap<>());
                Throwable primary = primaryFailure(failure);
                Throwable root = primary;
                String sqlState = "-";
                for (Throwable cause = primary; cause != null && chain.add(cause); cause = cause.getCause()) {
                    root = cause;
                    if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                        sqlState = sql.getSQLState();
                    }
                }
                if (Collections.disjoint(reported.get(), chain)) {
                    LOGGER.error(
                        "event=worker_operation status=FAILED operation={} route={} durationMs={} sqlState={} exceptionType={} rootCause={}{}",
                        operation, route, millis(nanoTime.getAsLong() - startedAtNanos), sqlState,
                        failure.getClass().getSimpleName(), root.getClass().getSimpleName(), context(operation, route));
                }
                reported.get().addAll(chain);
                reported.get().add(failure);
                throw failure;
            } finally {
                notifyCompletedSafely(operation, route, startedAtNanos, failed);
                if (outermost) {
                    reported.remove();
                }
            }
        }

        /** 진단 시계와 수신자의 실패를 격리해 업무 반환값·예외와 바깥 호출의 정리 동작을 보존한다. */
        private void notifyCompletedSafely(
            String operation,
            Object route,
            final long startedAtNanos,
            final boolean failed
        ) {
            try {
                listener.completed(operation, route, nanoTime.getAsLong() - startedAtNanos, failed);
            } catch (RuntimeException diagnosticFailure) {
                return;
            }
        }

        private void slow(
            String operation,
            Object route,
            final long elapsedNanos,
            final int rowCount
        ) {
            if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
                // 메서드 반환을 commit 성공으로 기록하지 않는다. 실패한 transaction의 느린 조회도 구분한다.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(final int status) {
                        String outcome = switch (status) {
                            case STATUS_COMMITTED -> "COMMITTED";
                            case STATUS_ROLLED_BACK -> "ROLLED_BACK";
                            default -> "UNKNOWN";
                        };
                        writeSlow(operation, route, elapsedNanos, rowCount, outcome);
                    }
                });
                return;
            }
            // 반환한 호출의 시간이며 외부 transaction의 존재나 영속화를 추측하지 않는다.
            writeSlow(operation, route, elapsedNanos, rowCount, "NOT_OBSERVED");
        }

        private static int returnedRowCount(Object result) {
            if (result instanceof Collection<?> collection) {
                return collection.size();
            }
            return -1;
        }

        private static Throwable primaryFailure(RuntimeException failure) {
            if (failure instanceof TransactionSystemException transactionFailure
                && transactionFailure.getApplicationException() != null) {
                return transactionFailure.getApplicationException();
            }
            return failure;
        }

        private void writeSlow(
            String operation,
            Object route,
            final long elapsedNanos,
            final int rowCount,
            String outcome
        ) {
            LOGGER.warn(
                "event=worker_operation status=SLOW operation={} route={} durationMs={} returnedRows={} transactionOutcome={}{}",
                operation, route, millis(elapsedNanos), rowCount, outcome, context(operation, route));
        }

        synchronized void warn(String operation, Object route, String reason) {
            WarningKey key = new WarningKey(operation, String.valueOf(route), reason);
            final long nowNanos = nanoTime.getAsLong();
            Long last = warnings.get(key);
            if (last != null && nowNanos - last < WARNING_NANOS) {
                return;
            }
            if (!warnings.containsKey(key) && warnings.size() >= 256) {
                warnings.remove(warnings.keySet().iterator().next());
            }
            warnings.put(key, nowNanos);
            LOGGER.warn("event=worker_operation status=DEFERRED operation={} route={} reason={}{}", operation, route,
                reason, context(operation, route));
        }

        synchronized void recovered(String operation, Object route) {
            warnings.keySet().removeIf(key -> Objects.equals(key.operation(), operation)
                    && key.route().equals(String.valueOf(route)));
        }

        private record WarningKey(String operation, String route, String reason) {
        }

        private static long millis(final long nanos) {
            return TimeUnit.NANOSECONDS.toMillis(nanos);
        }
    }
}
