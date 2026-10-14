package com.gustler.backend.arrivals.application;

import com.gustler.backend.arrival.ArrivalRefresh;
import com.gustler.backend.arrival.ArrivalRefresh.Status;
import com.gustler.backend.arrival.ArrivalSnapshot;
import com.gustler.backend.arrivals.api.ArrivalCollectionPolicy;
import com.gustler.backend.arrivals.api.RefreshStationArrivals;
import com.gustler.backend.quota.api.ArrivalCallQuota;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 단일 활성 수집 Worker의 실행 제어. 잠금 안에서는 메모리만 변경한다. */
public final class ArrivalRefreshCoordinator implements RefreshStationArrivals {
    private static final Logger log = LoggerFactory.getLogger(ArrivalRefreshCoordinator.class);
    private static final Duration POLL = Duration.ofSeconds(2);
    private final ArrivalStore store;
    private final ArrivalSource source;
    private final ArrivalCallQuota quota;
    private final Executor executor;
    private final ArrivalCollectionPolicy policy;
    private final Clock clock;
    private final LongSupplier ticker;
    private final Map<String, State> states = new HashMap<>();
    private final long startupUntil;
    private Instant quotaBlockedUntil = Instant.MIN;
    private long serviceBlockedUntil;
    private long lastRefill;
    private double tokens;

    public ArrivalRefreshCoordinator(ArrivalStore store, ArrivalSource source, ArrivalCallQuota quota,
        Executor executor, ArrivalCollectionPolicy policy, Clock clock, LongSupplier ticker) {
        this.store = store; this.source = source; this.quota = quota; this.executor = executor;
        this.policy = policy; this.clock = clock; this.ticker = ticker;
        this.lastRefill = ticker.getAsLong();
        this.startupUntil = lastRefill + policy.startupDelay().toNanos();
        this.serviceBlockedUntil = lastRefill;
        this.tokens = policy.burst();
    }

    public ArrivalRefresh request(String stationId) {
        ArrivalSnapshot.requireStation(stationId);
        Job job;
        synchronized (this) {
            long now = ticker.getAsLong();
            State previous = states.get(stationId);
            if (previous != null && previous.job != null) return reply(Status.IN_PROGRESS, "COLLECTING", POLL);
            if (now < startupUntil) return waitFor("STARTING", startupUntil - now);
            if (clock.instant().isBefore(quotaBlockedUntil)) {
                return new ArrivalRefresh(Status.DEFERRED, "DAILY_QUOTA_EXHAUSTED", quotaBlockedUntil);
            }
            if (now < serviceBlockedUntil) return waitFor("RATE_LIMITED", serviceBlockedUntil - now);
            if (previous != null && now < previous.nextAllowed) {
                return waitFor(previous.reason, previous.nextAllowed - now);
            }
            if (previous == null && states.size() >= policy.maximumStates()) {
                states.entrySet().removeIf(entry -> entry.getValue().job == null
                    && now >= entry.getValue().nextAllowed);
                if (states.size() >= policy.maximumStates()) return reply(Status.DEFERRED, "BUSY", POLL);
            }
            State state = previous == null ? new State() : previous;
            job = new Job(UUID.randomUUID(), now + policy.queueWait().toNanos());
            state.job = job;
            states.put(stationId, state);
        }
        try { executor.execute(() -> collect(stationId, job)); }
        catch (RejectedExecutionException full) {
            defer(stationId, job, "BUSY", POLL);
            return reply(Status.DEFERRED, "BUSY", POLL);
        }
        return reply(Status.ACCEPTED, "COLLECTING", POLL);
    }

    private void collect(String stationId, Job job) {
        try {
            if (expired(job)) { defer(stationId, job, "QUEUE_EXPIRED", POLL); return; }
            if (!store.supported(stationId)) {
                synchronized (this) {
                    State state = states.get(stationId);
                    if (state != null && state.job == job) states.remove(stationId);
                }
                return;
            }
            var latest = store.latest(stationId);
            if (latest.isPresent() && !latest.get().refreshDueAt(clock.instant())) {
                defer(stationId, job, "NOT_DUE", Duration.between(clock.instant(), latest.get().refreshAfterAt()));
                return;
            }
            if (expired(job)) { defer(stationId, job, "QUEUE_EXPIRED", POLL); return; }
            synchronized (this) {
                if (clock.instant().isBefore(quotaBlockedUntil)) {
                    defer(stationId, job, "DAILY_QUOTA_EXHAUSTED", Duration.between(clock.instant(), quotaBlockedUntil));
                    return;
                }
                if (ticker.getAsLong() < serviceBlockedUntil) {
                    defer(stationId, job, "RATE_LIMITED", Duration.ofNanos(serviceBlockedUntil - ticker.getAsLong()));
                    return;
                }
            }
            Duration rateWait = acquireRate();
            if (!rateWait.isZero()) { defer(stationId, job, "RATE_LIMITED", rateWait); return; }
            var reservation = quota.reserve(clock.instant());
            if (reservation.isEmpty()) { exhausted(stationId, job); return; }
            if (!reservation.get().covers(clock.instant())) {
                reservation = quota.reserve(clock.instant());
                if (reservation.isEmpty() || !reservation.get().covers(clock.instant())) {
                    exhausted(stationId, job); return;
                }
            }
            synchronized (this) { job.phase = Phase.COLLECTING; }
            ArrivalSource.Result result = source.fetch(stationId, reservation.get().keyAlias());
            if (result instanceof ArrivalSource.Success success) {
                ArrivalSnapshot snapshot = new ArrivalSnapshot(job.id, stationId, success.receivedAt(),
                    success.receivedAt().plus(policy.refreshInterval()), success.receivedAt().plus(policy.validity()),
                    success.sourceQueryTime(), success.routes());
                synchronized (this) { job.phase = Phase.SAVING; }
                save(snapshot);
                synchronized (this) {
                    State state = states.get(stationId);
                    if (state != null && state.job == job) {
                        state.failures = 0;
                        finish(state, "NOT_DUE", Duration.between(clock.instant(), snapshot.refreshAfterAt()));
                    }
                }
                log.info("event=arrival_collected stationId={} routes={}", stationId, snapshot.routes().size());
            } else {
                var failed = (ArrivalSource.Failed) result;
                ArrivalSource.Reason reason = failed.reason();
                log.warn("event=arrival_source_failed stationId={} reason={}", stationId, reason);
                if (reason == ArrivalSource.Reason.DAILY_QUOTA_EXCEEDED
                    || reason == ArrivalSource.Reason.KEY_REJECTED) quota.exclude(reservation.get());
                fail(stationId, job, reason.name(), failed.retryAfter());
                if (reason == ArrivalSource.Reason.RATE_LIMITED) {
                    synchronized (this) {
                        Duration delay = failed.retryAfter() != null && failed.retryAfter().compareTo(policy.retryBase()) > 0
                            ? failed.retryAfter() : policy.retryBase();
                        serviceBlockedUntil = Math.max(serviceBlockedUntil, ticker.getAsLong() + delay.toNanos());
                    }
                }
            }
        } catch (RuntimeException failure) {
            fail(stationId, job, "STORAGE_OR_COLLECTION_ERROR");
            // 예외의 본문이나 URL에 인증 정보가 있을 수 있어 출력하지 않는다.
            log.warn("event=arrival_collection_failed stationId={} exceptionType={}", stationId,
                failure.getClass().getSimpleName());
        }
    }

    private void save(ArrivalSnapshot snapshot) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < policy.saveAttempts(); attempt++) {
            try { store.save(snapshot); return; }
            catch (RuntimeException failure) { last = failure; }
        }
        throw last;
    }

    private synchronized boolean expired(Job job) { return ticker.getAsLong() >= job.startBy; }

    private synchronized Duration acquireRate() {
        long now = ticker.getAsLong();
        tokens = Math.min(policy.burst(), tokens + Math.max(0, now - lastRefill) / 1e9 * policy.callsPerSecond());
        lastRefill = now;
        if (tokens >= 1) { tokens--; return Duration.ZERO; }
        return Duration.ofNanos((long) Math.ceil((1 - tokens) / policy.callsPerSecond() * 1e9));
    }

    private synchronized void exhausted(String stationId, Job job) {
        quotaBlockedUntil = clock.instant().atZone(ArrivalCallQuota.KOREA).toLocalDate()
            .plusDays(1).atStartOfDay(ArrivalCallQuota.KOREA).toInstant();
        defer(stationId, job, "DAILY_QUOTA_EXHAUSTED", Duration.between(clock.instant(), quotaBlockedUntil));
    }

    private synchronized void fail(String stationId, Job job, String reason) {
        fail(stationId, job, reason, null);
    }

    private synchronized void fail(String stationId, Job job, String reason, Duration minimum) {
        State state = states.get(stationId);
        if (state == null || state.job != job) return;
        state.failures = Math.min(state.failures + 1, 30);
        long factor = 1L << Math.min(state.failures - 1, 20);
        Duration delay = policy.retryBase().multipliedBy(factor);
        if (delay.compareTo(policy.retryMaximum()) > 0) delay = policy.retryMaximum();
        if (minimum != null && minimum.compareTo(delay) > 0) delay = minimum;
        finish(state, reason, delay);
    }

    private synchronized void defer(String stationId, Job job, String reason, Duration delay) {
        State state = states.get(stationId);
        if (state != null && state.job == job) finish(state, reason, delay);
    }

    private void finish(State state, String reason, Duration delay) {
        state.job = null;
        state.reason = reason;
        state.nextAllowed = ticker.getAsLong() + Math.max(0, delay.toNanos());
    }

    private ArrivalRefresh waitFor(String reason, long nanos) {
        return reply(Status.DEFERRED, reason, Duration.ofNanos(Math.max(0, nanos)));
    }

    private ArrivalRefresh reply(Status status, String reason, Duration delay) {
        return new ArrivalRefresh(status, reason, clock.instant().plus(delay));
    }

    private enum Phase { QUEUED, COLLECTING, SAVING }
    private static final class Job {
        final UUID id;
        final long startBy;
        Phase phase = Phase.QUEUED;
        Job(UUID id, long startBy) { this.id = id; this.startBy = startBy; }
    }
    private static final class State {
        Job job;
        long nextAllowed;
        int failures;
        String reason = "NOT_DUE";
    }
}
