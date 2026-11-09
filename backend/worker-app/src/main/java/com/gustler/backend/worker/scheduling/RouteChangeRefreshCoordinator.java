package com.gustler.backend.worker.scheduling;

import com.gustler.backend.observations.api.ObservationRouteMismatchDetected;
import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routecatalog.api.RouteVersionChanged;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.RouteGeometryProperties;
import com.gustler.backend.worker.scheduling.JdbcRouteRefreshStateRepository.State;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Signals wake collection immediately; the daily/startup sweep also recovers work after process loss. */
@Component
@ConditionalOnExpression("${collection.enabled:false} or ${route-geometry.enabled:false}")
public class RouteChangeRefreshCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(RouteChangeRefreshCoordinator.class);
    private static final int MAX_ATTEMPTS = 5;
    private final RefreshRouteCatalog catalog;
    private final RefreshRouteGeometry geometry;
    private final JdbcRouteRefreshStateRepository states;
    private final Executor executor;
    private final RouteGeometryProperties geometryProperties;
    private final Set<String> geometryRoutes;
    private final boolean inheritedCollectionScope;
    private final Clock clock;
    private final Duration mismatchCooldown;
    private final Map<Long, VersionRequest> versions = new ConcurrentHashMap<>();
    private final Map<Long, VersionBudget> versionBudgets = new ConcurrentHashMap<>();
    private final Map<String, MismatchRequest> mismatches = new ConcurrentHashMap<>();
    private final Map<String, CheckedMismatch> checked = new ConcurrentHashMap<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile boolean ready;

    public RouteChangeRefreshCoordinator(RefreshRouteCatalog catalog, RefreshRouteGeometry geometry,
            JdbcRouteRefreshStateRepository states, @Qualifier("routeGeometryExecutor") Executor executor,
            CollectionProperties collection, RouteGeometryProperties geometryProperties, Optional<Clock> clock,
            @Value("${route-geometry.change-refresh.mismatch-cooldown:PT1M}") Duration mismatchCooldown) {
        if (mismatchCooldown.isNegative() || mismatchCooldown.isZero())
            throw new IllegalArgumentException("Mismatch cooldown must be positive");
        this.catalog = catalog;
        this.geometry = geometry;
        this.states = states;
        this.executor = executor;
        this.geometryProperties = geometryProperties;
        this.inheritedCollectionScope = geometryProperties.routeIds().isEmpty();
        this.geometryRoutes = Set.copyOf(geometryProperties.routeIds().isEmpty()
                ? collection.routeIds() : geometryProperties.routeIds());
        this.clock = clock.orElseGet(Clock::systemUTC);
        this.mismatchCooldown = mismatchCooldown;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void versionChanged(RouteVersionChanged event) {
        requestVersion(event.routeId(), new VersionRequest(event.routeVersionId(), 0, clock.instant()));
        wake();
    }

    @EventListener
    public void observationMismatch(ObservationRouteMismatchDetected event) {
        var previous = checked.get(event.sourceRouteId());
        Instant due = previous != null && previous.versionId() == event.routeVersionId()
                && previous.nextCheck().isAfter(clock.instant()) ? previous.nextCheck() : clock.instant();
        requestMismatch(event.sourceRouteId(), new MismatchRequest(event.routeVersionId(), 0, due));
        wake();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void applicationReady() {
        ready = true;
        if (hasDueWork()) wake();
    }

    /** Only retries signalled requests. No periodic upstream scan is performed here. */
    @Scheduled(fixedDelayString = "${route-geometry.change-refresh.retry-delay-ms:10000}",
            scheduler = "collectionTaskScheduler")
    public void retryPending() {
        if (hasDueWork()) wake();
    }

    private void wake() {
        if (!ready) return;
        if (!draining.compareAndSet(false, true)) return;
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException rejected) {
            draining.set(false);
            LOG.warn("event=route_change_refresh_deferred reason=QUEUE_FULL");
        }
    }

    private void drain() {
        try {
            while (true) {
                var version = takeDue(versions);
                if (version != null) {
                    refreshVersion(version.getKey(), version.getValue());
                    continue;
                }
                var mismatch = takeDue(mismatches);
                if (mismatch == null) return;
                refreshAfterMismatch(mismatch.getKey(), mismatch.getValue());
            }
        } finally {
            draining.set(false);
            if (hasDueWork()) wake();
        }
    }

    private void refreshAfterMismatch(String sourceId, MismatchRequest request) {
        try {
            var current = states.findBySourceId(sourceId);
            if (current.isEmpty()) { checked.remove(sourceId); return; }
            var state = current.get();
            // A delayed observation from the old version must not force another upstream refresh.
            if (state.versionId() != request.versionId()) {
                requestMissingGeometry(state);
                return;
            }
            var previous = checked.get(sourceId);
            if (previous != null && previous.versionId() == request.versionId()
                    && previous.nextCheck().isAfter(clock.instant())) {
                requestMismatch(sourceId, new MismatchRequest(request.versionId(), request.attempts(), previous.nextCheck()));
                return;
            }
            checked.put(sourceId, new CheckedMismatch(request.versionId(), clock.instant().plus(mismatchCooldown)));
            if (catalog.refresh(sourceId, OffsetDateTime.now(clock)).isEmpty()) {
                retryMismatch(sourceId, request);
                return;
            }
            // The version-change event is normally queued too. Missing-shape checks merge the two paths.
            states.findBySourceId(sourceId).ifPresent(this::requestMissingGeometry);
        } catch (RuntimeException failure) {
            LOG.warn("event=route_change_catalog_refresh_failed routeId={} routeVersionId={} failureType={}",
                    sourceId, request.versionId(), failure.getClass().getSimpleName());
            retryMismatch(sourceId, request);
        }
    }

    private void requestMissingGeometry(State state) {
        if (coversGeometry(state) && !state.hasGeometry())
            requestVersion(state.routeId(), new VersionRequest(state.versionId(), 0, clock.instant()));
    }

    private void refreshVersion(long routeId, VersionRequest request) {
        VersionRequest active = request;
        try {
            var current = states.findByRouteId(routeId);
            if (current.isEmpty()) return;
            var state = current.get();
            if (state.versionId() != request.versionId()) {
                requestMissingGeometry(state);
                return;
            }
            if (!coversGeometry(state) || state.hasGeometry()) return;
            int attempt = reserveSourceAttempt(routeId, request.versionId());
            if (attempt == 0) return;
            active = new VersionRequest(request.versionId(), attempt - 1, request.due());
            Outcome outcome = geometry.ensure(state.publicRouteId(), OffsetDateTime.now(clock));
            if (outcome == Outcome.UPDATED || outcome == Outcome.UNCHANGED
                    || outcome == Outcome.RECOVERY_SCHEDULED || outcome == Outcome.RECOVERY_EXHAUSTED) return;
            if (outcome == Outcome.REFERENCE_CHANGED) {
                var latest = states.findByRouteId(routeId);
                if (latest.isPresent() && latest.get().versionId() != request.versionId())
                    requestMissingGeometry(latest.get());
                else retryVersion(routeId, active);
                return;
            }
            retryVersion(routeId, active);
        } catch (RuntimeException failure) {
            LOG.warn("event=route_change_geometry_refresh_failed routeId={} routeVersionId={} failureType={}",
                    routeId, request.versionId(), failure.getClass().getSimpleName());
            retryVersion(routeId, active);
        }
    }

    private int reserveSourceAttempt(long routeId, long versionId) {
        var previous = versionBudgets.get(routeId);
        if (previous != null && previous.versionId() == versionId && previous.attempts() >= MAX_ATTEMPTS)
            return 0;
        int attempts = previous != null && previous.versionId() == versionId ? previous.attempts() + 1 : 1;
        versionBudgets.put(routeId, new VersionBudget(versionId, attempts));
        return attempts;
    }

    private boolean coversGeometry(State state) {
        return geometryProperties.enabled()
                && (geometryProperties.includeCatalog() || geometryRoutes.contains(
                    inheritedCollectionScope ? state.sourceRouteId() : state.publicRouteId()));
    }

    private void retryVersion(long routeId, VersionRequest request) {
        int attempts = request.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            LOG.warn("event=route_change_geometry_refresh_exhausted routeId={} routeVersionId={}", routeId, request.versionId());
            return;
        }
        requestVersion(routeId, new VersionRequest(request.versionId(), attempts, clock.instant().plus(retryDelay(attempts))));
    }

    private void retryMismatch(String routeId, MismatchRequest request) {
        int attempts = request.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            LOG.warn("event=route_change_catalog_refresh_exhausted routeId={} routeVersionId={}", routeId, request.versionId());
            return;
        }
        requestMismatch(routeId, new MismatchRequest(request.versionId(), attempts,
                clock.instant().plus(retryDelay(attempts).compareTo(mismatchCooldown) > 0 ? retryDelay(attempts) : mismatchCooldown)));
    }

    private static Duration retryDelay(int attempts) {
        return Duration.ofSeconds(Math.min(300, 10L << Math.min(attempts - 1, 5)));
    }

    private void requestVersion(long routeId, VersionRequest request) {
        versions.merge(routeId, request, (old, incoming) -> later(old, incoming));
    }

    private void requestMismatch(String routeId, MismatchRequest request) {
        mismatches.merge(routeId, request, (old, incoming) -> later(old, incoming));
    }

    private static <T extends Request> T later(T old, T incoming) {
        if (old.versionId() != incoming.versionId()) return old.versionId() > incoming.versionId() ? old : incoming;
        if (old.attempts() != incoming.attempts()) return old.attempts() > incoming.attempts() ? old : incoming;
        return old.due().isBefore(incoming.due()) ? old : incoming;
    }

    private <K, T extends Request> Map.Entry<K, T> takeDue(Map<K, T> requests) {
        for (var entry : requests.entrySet()) {
            if (!entry.getValue().due().isAfter(clock.instant()) && requests.remove(entry.getKey(), entry.getValue()))
                return Map.entry(entry.getKey(), entry.getValue());
        }
        return null;
    }

    private boolean hasDueWork() {
        return versions.values().stream().anyMatch(r -> !r.due().isAfter(clock.instant()))
                || mismatches.values().stream().anyMatch(r -> !r.due().isAfter(clock.instant()));
    }

    private interface Request { long versionId(); int attempts(); Instant due(); }
    private record VersionRequest(long versionId, int attempts, Instant due) implements Request {}
    private record MismatchRequest(long versionId, int attempts, Instant due) implements Request {}
    private record CheckedMismatch(long versionId, Instant nextCheck) {}
    private record VersionBudget(long versionId, int attempts) {}
}
