package com.gustler.backend.forecasting.application.evaluation;

import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 한 초기화 호출에서만 사용하며 DB 조회나 ThreadLocal 상태를 만들지 않는다. */
final class SameDayInitializationAttempt {
    enum Stage { CONFIGURE, LOCK, CHECK, SOURCE, SAVE, COMMIT }
    private final LongSupplier nanoTime;
    private final long started;
    private final EnumMap<Stage, Long> durations = new EnumMap<>(Stage.class);
    private Stage stage = Stage.CONFIGURE;
    private String status = "FAILED";
    private String sqlState = "-";
    private Long totalMs;

    SameDayInitializationAttempt() { this(System::nanoTime); }
    SameDayInitializationAttempt(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.started = nanoTime.getAsLong();
    }

    <T> T measure(Stage next, Supplier<T> action) {
        stage = next;
        long start = nanoTime.getAsLong();
        try { return action.get(); }
        finally { durations.put(next, millis(nanoTime.getAsLong() - start)); }
    }

    void run(Stage next, Runnable action) {
        measure(next, () -> { action.run(); return null; });
    }

    void awaitingCommit() { stage = Stage.COMMIT; }

    void completed(boolean initialized) {
        status = initialized ? "COMPLETED" : "SKIPPED";
        finish();
    }

    void failed(RuntimeException failure) {
        status = "FAILED";
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                sqlState = sql.getSQLState();
            }
        }
        finish();
    }

    String status() { return status; }
    String failedStage() { return status.equals("FAILED") ? stage.name() : "-"; }
    String sqlState() { return sqlState; }
    boolean sourceAttempted() { return durations.containsKey(Stage.SOURCE); }
    Long lockAcquireMs() { return durations.get(Stage.LOCK); }
    Long sourceQueryMs() { return durations.get(Stage.SOURCE); }
    Long totalMs() { return totalMs; }
    private void finish() { totalMs = millis(nanoTime.getAsLong() - started); }
    private static long millis(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }
}
