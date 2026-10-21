package com.gustler.backend.arrivals.application;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrivals.api.ArrivalCollectionPolicy;
import com.gustler.backend.arrivals.api.RefreshStationArrivals;
import com.gustler.backend.arrivals.domain.ArrivalRateLimit;
import com.gustler.backend.arrivals.domain.ArrivalRetryPolicy;
import com.gustler.backend.arrivals.domain.ArrivalSource;
import com.gustler.backend.arrivals.domain.ArrivalSource.Failed;
import com.gustler.backend.arrivals.domain.ArrivalSource.Reason;
import com.gustler.backend.arrivals.domain.ArrivalSource.Success;
import com.gustler.backend.arrivals.domain.ArrivalStore;
import com.gustler.backend.arrivals.domain.StationId;
import com.gustler.backend.quota.api.ArrivalCallQuota;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 단일 활성 수집 Worker의 실행 제어. 잠금 안에서는 메모리만 변경한다. */
public final class ArrivalRefreshCoordinator implements RefreshStationArrivals {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArrivalRefreshCoordinator.class);
    private static final Duration POLL = Duration.ofSeconds(2);
    private static final Duration COLLECTING_POLL = Duration.ofSeconds(1);
    private final ArrivalStore store;
    private final ArrivalSource source;
    private final ArrivalCallQuota quota;
    private final Executor executor;
    private final ArrivalCollectionPolicy policy;
    private final Clock clock;
    private final LongSupplier ticker;
    private final Map<StationId, State> states = new HashMap<>();
    private final long startupUntilNanos;
    private final ArrivalRateLimit rateLimit;
    private final ArrivalRetryPolicy retryPolicy;
    private final Semaphore waitingRequests = new Semaphore(32);

    private Instant quotaBlockedUntil = Instant.MIN;
    private long serviceBlockedUntilNanos;

    public ArrivalRefreshCoordinator(
        ArrivalStore store,
        ArrivalSource source,
        ArrivalCallQuota quota,
        Executor executor,
        ArrivalCollectionPolicy policy,
        Clock clock,
        LongSupplier ticker
    ) {
        this.store = store;
        this.source = source;
        this.quota = quota;
        this.executor = executor;
        this.policy = policy;
        this.clock = clock;
        this.ticker = ticker;
        final long startedAtNanos = ticker.getAsLong();
        this.startupUntilNanos = startedAtNanos + policy.startupDelay().toNanos();
        this.serviceBlockedUntilNanos = startedAtNanos;
        this.rateLimit = new ArrivalRateLimit(policy.callsPerSecond(), policy.burst(), startedAtNanos);
        this.retryPolicy = new ArrivalRetryPolicy(policy.retryBase(), policy.retryMaximum());
    }

    @Override
    public ArrivalRefresh request(String value) {
        StationId stationId = new StationId(value);
        Job job;
        synchronized (this) {
            final long nowNanos = ticker.getAsLong();
            State previous = states.get(stationId);
            if (previous != null && previous.job != null) {
                return reply(Status.IN_PROGRESS, "COLLECTING", COLLECTING_POLL);
            }
            if (nowNanos < startupUntilNanos) {
                return waitFor("STARTING", startupUntilNanos - nowNanos);
            }
            if (clock.instant().isBefore(quotaBlockedUntil)) {
                return new ArrivalRefresh(Status.DEFERRED, "DAILY_QUOTA_EXHAUSTED", quotaBlockedUntil);
            }
            if (nowNanos < serviceBlockedUntilNanos) {
                return waitFor("RATE_LIMITED", serviceBlockedUntilNanos - nowNanos);
            }
            if (previous != null && nowNanos < previous.nextAllowedNanos) {
                return waitFor(previous.reason, previous.nextAllowedNanos - nowNanos);
            }
            if (previous == null && states.size() >= policy.maximumStates()) {
                states.entrySet().removeIf(entry -> entry.getValue().job == null
                    && nowNanos >= entry.getValue().nextAllowedNanos);
                if (states.size() >= policy.maximumStates()) {
                    return reply(Status.DEFERRED, "BUSY", POLL);
                }
            }
            State state = previous;
            if (state == null) {
                state = new State();
            }
            job = new Job(UUID.randomUUID(), nowNanos + policy.queueWait().toNanos());
            state.job = job;
            states.put(stationId, state);
        }
        try {
            executor.execute(() -> collect(stationId, job));
        }
        catch (RejectedExecutionException full) {
            defer(stationId, job, "BUSY", POLL);
            return reply(Status.DEFERRED, "BUSY", POLL);
        }
        return reply(Status.ACCEPTED, "COLLECTING", COLLECTING_POLL);
    }

    @Override
    public ArrivalRefresh requestAndAwait(String value, Duration maximumWait) {
        if (isInvalidMaximumWait(maximumWait)) {
            throw new IllegalArgumentException("ETA 수집 완료 대기 한도 초과");
        }

        final long deadlineNanos = System.nanoTime() + maximumWait.toNanos();
        ArrivalRefresh initial = request(value);
        if (!shouldWaitForCompletion(maximumWait, initial)) {
            return initial;
        }

        try {
            StationId stationId = new StationId(value);
            Job job;
            synchronized (this) {
                State state = states.get(stationId);
                job = null;
                if (state != null) {
                    job = state.job;
                }
            }
            if (job != null) {
                final long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos > 0) {
                    job.completed.get(remainingNanos, TimeUnit.NANOSECONDS);
                }
            }
            return afterWait(stationId, initial);
        } catch (TimeoutException timeout) {
            return new ArrivalRefresh(Status.IN_PROGRESS, "COLLECTING", initial.suggestedRefreshAt());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return initial;
        } catch (ExecutionException failure) {
            return initial;
        } finally {
            waitingRequests.release();
        }
    }

    private boolean isInvalidMaximumWait(Duration maximumWait) {
        if (maximumWait == null || maximumWait.isNegative()) {
            return true;
        }
        return maximumWait.compareTo(Duration.ofMillis(ArrivalRefresh.MAX_WAIT_MILLIS)) > 0;
    }

    private boolean shouldWaitForCompletion(Duration maximumWait, ArrivalRefresh initial) {
        if (maximumWait.isZero() || !isCollectionInProgress(initial)) {
            return false;
        }
        return waitingRequests.tryAcquire();
    }

    private boolean isCollectionInProgress(ArrivalRefresh initial) {
        Status status = initial.status();
        return status == Status.ACCEPTED || status == Status.IN_PROGRESS;
    }

    private synchronized ArrivalRefresh afterWait(StationId stationId, ArrivalRefresh initial) {
        State state = states.get(stationId);
        if (state == null) {
            return initial;
        }
        if (state.job != null) {
            return new ArrivalRefresh(Status.IN_PROGRESS, "COLLECTING", initial.suggestedRefreshAt());
        }
        Duration untilNext = Duration.ofNanos(Math.max(0, state.nextAllowedNanos - ticker.getAsLong()));
        if ("NOT_DUE".equals(state.reason)) {
            return reply(Status.IDLE, null, untilNext);
        }
        return reply(Status.DEFERRED, state.reason, untilNext);
    }

    private void collect(StationId stationId, Job job) {
        try {
            if (deferIfQueueExpired(stationId, job)) {
                return;
            }
            if (!store.supported(stationId.value())) {
                removeUnsupportedStationJob(stationId, job);
                return;
            }
            if (deferIfLatestSnapshotIsFresh(stationId, job)) {
                return;
            }
            if (deferIfQueueExpired(stationId, job)) {
                return;
            }
            if (deferIfCollectionIsBlocked(stationId, job) || deferIfRateLimitReached(stationId, job)) {
                return;
            }
            Optional<ArrivalCallQuota.Reservation> reservation = reserveCoveredQuota();
            if (reservation.isEmpty()) {
                exhausted(stationId, job);
                return;
            }
            ArrivalSource.Result result = source.fetch(stationId.value(), reservation.get().keyAlias());
            recordCollectionResult(stationId, job, reservation.get(), result);
        } catch (RuntimeException failure) {
            fail(stationId, job, "STORAGE_OR_COLLECTION_ERROR");
            // 예외의 본문이나 URL에 인증 정보가 있을 수 있어 출력하지 않는다.
            LOGGER.warn("event=arrival_collection_failed stationId={} exceptionType={}", stationId.value(),
                failure.getClass().getSimpleName());
        } finally {
            // 수신 저장·상태 정리가 끝난 뒤 알린다. 대기하는 HTTP 요청은 DB 연결을 점유하지 않는다.
            job.completed.complete(null);
        }
    }

    private boolean deferIfQueueExpired(StationId stationId, Job job) {
        if (!expired(job)) {
            return false;
        }
        defer(stationId, job, "QUEUE_EXPIRED", POLL);
        return true;
    }

    private synchronized void removeUnsupportedStationJob(StationId stationId, Job job) {
        State state = states.get(stationId);
        if (state != null && state.job == job) {
            states.remove(stationId);
        }
    }

    private boolean deferIfLatestSnapshotIsFresh(StationId stationId, Job job) {
        Optional<ArrivalSnapshot> latest = store.latest(stationId.value());
        if (latest.isEmpty() || !clock.instant().isBefore(latest.get().refreshAfterAt())) {
            return false;
        }
        defer(stationId, job, "NOT_DUE", Duration.between(clock.instant(), latest.get().refreshAfterAt()));
        return true;
    }

    private synchronized boolean deferIfCollectionIsBlocked(StationId stationId, Job job) {
        if (clock.instant().isBefore(quotaBlockedUntil)) {
            defer(stationId, job, "DAILY_QUOTA_EXHAUSTED", Duration.between(clock.instant(), quotaBlockedUntil));
            return true;
        }
        if (ticker.getAsLong() < serviceBlockedUntilNanos) {
            defer(stationId, job, "RATE_LIMITED",
                Duration.ofNanos(serviceBlockedUntilNanos - ticker.getAsLong()));
            return true;
        }
        return false;
    }

    private boolean deferIfRateLimitReached(StationId stationId, Job job) {
        Duration rateWait = acquireRate();
        if (rateWait.isZero()) {
            return false;
        }
        defer(stationId, job, "RATE_LIMITED", rateWait);
        return true;
    }

    private Optional<ArrivalCallQuota.Reservation> reserveCoveredQuota() {
        Optional<ArrivalCallQuota.Reservation> reservation = quota.reserve(clock.instant());
        if (reservation.isEmpty() || reservation.get().covers(clock.instant())) {
            return reservation;
        }
        Optional<ArrivalCallQuota.Reservation> renewed = quota.reserve(clock.instant());
        if (renewed.isPresent() && renewed.get().covers(clock.instant())) {
            return renewed;
        }
        return Optional.empty();
    }

    private void recordCollectionResult(
        StationId stationId,
        Job job,
        ArrivalCallQuota.Reservation reservation,
        ArrivalSource.Result result
    ) {
        if (result instanceof Success success) {
            saveSuccessfulCollection(stationId, job, success);
            return;
        }
        recordFailedCollection(stationId, job, reservation, (Failed) result);
    }

    private void saveSuccessfulCollection(StationId stationId, Job job, Success success) {
        ArrivalSnapshot snapshot = new ArrivalSnapshot(job.id, stationId.value(), success.receivedAt(),
            success.receivedAt().plus(policy.refreshInterval()), success.receivedAt().plus(policy.validity()),
            success.sourceQueryTime(), success.routes());
        save(snapshot);
        finishSuccessfulJob(stationId, job, snapshot);
        LOGGER.info("event=arrival_collected stationId={} routes={}", stationId.value(), snapshot.routes().size());
    }

    private synchronized void finishSuccessfulJob(StationId stationId, Job job, ArrivalSnapshot snapshot) {
        State state = states.get(stationId);
        if (state != null && state.job == job) {
            state.failures = 0;
            finish(state, "NOT_DUE", Duration.between(clock.instant(), snapshot.refreshAfterAt()));
        }
    }

    private void recordFailedCollection(
        StationId stationId,
        Job job,
        ArrivalCallQuota.Reservation reservation,
        Failed failed
    ) {
        Reason reason = failed.reason();
        LOGGER.warn("event=arrival_source_failed stationId={} reason={}", stationId.value(), reason);
        if (reason == Reason.DAILY_QUOTA_EXCEEDED || reason == Reason.KEY_REJECTED) {
            quota.exclude(reservation);
        }
        fail(stationId, job, reason.name(), failed.retryAfter());
        if (reason == Reason.RATE_LIMITED) {
            blockServiceUntilRetryIsAllowed(failed);
        }
    }

    private synchronized void blockServiceUntilRetryIsAllowed(Failed failed) {
        Duration delay = retryDelay(failed);
        serviceBlockedUntilNanos = Math.max(serviceBlockedUntilNanos, ticker.getAsLong() + delay.toNanos());
    }

    private Duration retryDelay(Failed failed) {
        if (failed.retryAfter() != null && failed.retryAfter().compareTo(policy.retryBase()) > 0) {
            return failed.retryAfter();
        }
        return policy.retryBase();
    }

    private void save(ArrivalSnapshot snapshot) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < policy.saveAttempts(); attempt++) {
            try {
                store.save(snapshot);
                return;
            }
            catch (RuntimeException failure) {
                last = failure;
            }
        }
        throw last;
    }

    private synchronized boolean expired(Job job) {
        return ticker.getAsLong() >= job.startByNanos;
    }

    private synchronized Duration acquireRate() {
        return rateLimit.acquire(ticker.getAsLong());
    }

    private synchronized void exhausted(StationId stationId, Job job) {
        quotaBlockedUntil = clock.instant().atZone(ArrivalCallQuota.KOREA).toLocalDate()
            .plusDays(1).atStartOfDay(ArrivalCallQuota.KOREA).toInstant();
        defer(stationId, job, "DAILY_QUOTA_EXHAUSTED", Duration.between(clock.instant(), quotaBlockedUntil));
    }

    private synchronized void fail(StationId stationId, Job job, String reason) {
        fail(stationId, job, reason, null);
    }

    private synchronized void fail(
        StationId stationId,
        Job job,
        String reason,
        Duration minimum
    ) {
        State state = states.get(stationId);
        if (state == null || state.job != job) {
            return;
        }
        state.failures = Math.min(state.failures + 1, 30);
        finish(state, reason, retryPolicy.delay(state.failures, minimum));
    }

    private synchronized void defer(
        StationId stationId,
        Job job,
        String reason,
        Duration delay
    ) {
        State state = states.get(stationId);
        if (state != null && state.job == job) {
            finish(state, reason, delay);
        }
    }

    private void finish(State state, String reason, Duration delay) {
        state.job = null;
        state.reason = reason;
        state.nextAllowedNanos = ticker.getAsLong() + Math.max(0, delay.toNanos());
    }

    private ArrivalRefresh waitFor(String reason, final long nanos) {
        return reply(Status.DEFERRED, reason, Duration.ofNanos(Math.max(0, nanos)));
    }

    private ArrivalRefresh reply(Status status, String reason, Duration delay) {
        return new ArrivalRefresh(status, reason, clock.instant().plus(delay));
    }

    private static final class Job {
        final UUID id;
        final long startByNanos;
        final CompletableFuture<Void> completed = new CompletableFuture<>();

        Job(UUID id, final long startByNanos) {
            this.id = id;
            this.startByNanos = startByNanos;
        }
    }
    private static final class State {
        Job job;
        long nextAllowedNanos;
        int failures;
        String reason = "NOT_DUE";
    }
}
