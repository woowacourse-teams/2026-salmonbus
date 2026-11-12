package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 노선 트랜잭션 바깥에서 실패를 격리한다. 대기 작업은 DB에 두고 재시도 간격만 메모리에 둔다. */
public final class SettlementRouteRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettlementRouteRunner.class);
    private final Clock clock;
    private final String operation;
    private final Map<Long, Retry> retries = new ConcurrentHashMap<>();

    public SettlementRouteRunner(Clock clock, String operation) {
        this.clock = clock;
        this.operation = operation;
    }

    public void run(List<Long> versions, LongConsumer settle) {
        run(versions, settle, () -> true);
    }

    public void run(List<Long> versions, LongConsumer settle, BooleanSupplier continueWork) {
        retries.keySet().retainAll(versions);
        for (long version : versions) {
            if (!continueWork.getAsBoolean()) {
                return;
            }
            runRoute(version, settle);
        }
    }

    private void runRoute(final long version, LongConsumer settle) {
        Retry previous = retries.get(version);
        if (previous != null && clock.instant().isBefore(previous.nextAttemptAt())) {
            return;
        }
        try {
            WorkerOperationLog.run(operation, version, () -> settle.accept(version));
            retries.remove(version);
            if (previous != null) {
                LOGGER.info("event=settlement_route_recovered operation={} routeVersionId={} failures={}",
                    operation, version, previous.failures());
            }
        } catch (RuntimeException failure) {
            final int failures = previous == null ? 1 : previous.failures() + 1;
            final long delay = Math.min(60, 1L << Math.min(failures, 6));
            Instant nextAttemptAt = clock.instant().plusSeconds(delay);
            retries.put(version, new Retry(failures, nextAttemptAt));
            LOGGER.warn("event=settlement_route_failed operation={} routeVersionId={} failures={} retryAt={}"
                    + " exceptionType={}",
                operation, version, failures, nextAttemptAt, failure.getClass().getSimpleName());
        }
    }

    private record Retry(int failures, Instant nextAttemptAt) {
    }
}
