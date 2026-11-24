package com.gustler.backend.worker.scheduling;

import com.gustler.backend.observations.api.ObservationRouteStopMismatchDetected;
import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RouteCatalogPreparationRequested;
import com.gustler.backend.routecatalog.api.RouteVersionChanged;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.RouteGeometryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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

/** 별도 실행기에서 노선 준비·판본 변경·관측 불일치를 처리한다. 시작/정기 갱신은 프로세스 중단 후에도 준비 기회를 준다. */
@Component
@ConditionalOnExpression("${collection.enabled:false} or ${route-geometry.enabled:false}")
public class RouteCatalogAndGeometryRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(RouteCatalogAndGeometryRefresher.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final long MISSING_VERSION = 0;

    private final RefreshRouteCatalog catalog;
    private final RefreshRouteGeometry geometry;
    private final JdbcRouteRefreshStateRepository states;
    private final Executor executor;
    private final RouteGeometryProperties geometryProperties;
    private final Set<String> geometryRoutes;
    private final boolean inheritedCollectionScope;
    private final Clock clock;
    private final Duration mismatchCooldown;
    private final Map<Long, RouteCatalogAndGeometryRefresher.GeometryRefreshRequest> versions =
        new ConcurrentHashMap<>();
    private final Map<Long, RouteCatalogAndGeometryRefresher.GeometryRefreshAttempts> versionBudgets =
        new ConcurrentHashMap<>();
    private final Map<String, RouteCatalogAndGeometryRefresher.CatalogRefreshRequest> catalogRequests =
        new ConcurrentHashMap<>();
    private final Map<String, RouteCatalogAndGeometryRefresher.CatalogRefreshCooldown> catalogChecks =
        new ConcurrentHashMap<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    private volatile boolean ready;

    public RouteCatalogAndGeometryRefresher(
        RefreshRouteCatalog catalog,
        RefreshRouteGeometry geometry,
        JdbcRouteRefreshStateRepository states,
        @Qualifier("routeGeometryExecutor") Executor executor,
        CollectionProperties collection,
        RouteGeometryProperties geometryProperties,
        Optional<Clock> clock,
        @Value("${route-geometry.change-refresh.mismatch-cooldown:PT1M}") Duration mismatchCooldown
    ) {
        if (mismatchCooldown.isNegative() || mismatchCooldown.isZero()) {
            throw new IllegalArgumentException("Mismatch cooldown must be positive");
        }

        this.catalog = catalog;
        this.geometry = geometry;
        this.states = states;
        this.executor = executor;
        this.geometryProperties = geometryProperties;
        this.inheritedCollectionScope = geometryProperties.routeIds().isEmpty();

        List<String> selectedRoutes = geometryProperties.routeIds();
        if (selectedRoutes.isEmpty()) {
            selectedRoutes = collection.routeIds();
        }

        this.geometryRoutes = Set.copyOf(selectedRoutes);
        this.clock = clock.orElseGet(Clock::systemUTC);
        this.mismatchCooldown = mismatchCooldown;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void versionChanged(RouteVersionChanged event) {
        requestVersion(event.routeId(),
            new RouteCatalogAndGeometryRefresher.GeometryRefreshRequest(event.routeVersionId(), 0, clock.instant()));

        wake();
    }

    @EventListener
    public void catalogPreparationRequested(RouteCatalogPreparationRequested event) {
        queueCatalogRequest(event.sourceRouteId(), MISSING_VERSION);
    }

    @EventListener
    public void observationMismatch(ObservationRouteStopMismatchDetected event) {
        queueCatalogRequest(event.sourceRouteId(), event.routeVersionId());
    }

    private void queueCatalogRequest(String sourceRouteId, final long versionId) {
        RouteCatalogAndGeometryRefresher.CatalogRefreshCooldown previous = catalogChecks.get(sourceRouteId);
        Instant due = clock.instant();
        if (isCoolingDown(previous, versionId)) {
            due = previous.nextCheck();
        }
        queueCatalogRefresh(sourceRouteId,
            new RouteCatalogAndGeometryRefresher.CatalogRefreshRequest(versionId, 0, due));

        wake();
    }

    private boolean isCoolingDown(
        RouteCatalogAndGeometryRefresher.CatalogRefreshCooldown previous,
        final long versionId
    ) {
        return previous != null && previous.versionId() == versionId
            && previous.nextCheck().isAfter(clock.instant());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void applicationReady() {
        ready = true;

        if (hasDueWork()) {
            wake();
        }
    }

    /** Only retries signalled requests. No periodic upstream scan is performed here. */
    @Scheduled(fixedDelayString = "${route-geometry.change-refresh.retry-delay-ms:10000}",
            scheduler = "collectionTaskScheduler")
    public void retryPending() {
        if (hasDueWork()) {
            wake();
        }
    }

    private void wake() {
        if (!ready) {
            return;
        }

        if (!draining.compareAndSet(false, true)) {
            return;
        }

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
                Map.Entry<Long, RouteCatalogAndGeometryRefresher.GeometryRefreshRequest> geometryRequest =
                    takeDue(versions);
                if (geometryRequest != null) {
                    refreshVersion(geometryRequest.getKey(), geometryRequest.getValue());
                    continue;
                }

                Map.Entry<String, RouteCatalogAndGeometryRefresher.CatalogRefreshRequest> catalogRequest =
                    takeDue(catalogRequests);
                if (catalogRequest == null) {
                    return;
                }
                refreshCatalogIfNeeded(catalogRequest.getKey(), catalogRequest.getValue());
            }
        } finally {
            draining.set(false);

            if (hasDueWork()) {
                wake();
            }
        }
    }

    /** 이전 판본의 지연된 관측은 상류를 다시 조회하지 않고 최신 판본의 누락된 형상만 준비한다. */
    private void refreshCatalogIfNeeded(
        String sourceRouteId,
        RouteCatalogAndGeometryRefresher.CatalogRefreshRequest request
    ) {
        try {
            Optional<JdbcRouteRefreshStateRepository.State> currentRouteState = states.findBySourceId(sourceRouteId);
            if (currentRouteState.isEmpty()) {
                if (request.versionId() == MISSING_VERSION) {
                    refreshCatalog(sourceRouteId, request);
                    return;
                }
                catalogChecks.remove(sourceRouteId);
                return;
            }

            JdbcRouteRefreshStateRepository.State routeState = currentRouteState.get();
            if (routeState.versionId() != request.versionId()) {
                requestMissingGeometry(routeState);
                return;
            }

            RouteCatalogAndGeometryRefresher.CatalogRefreshCooldown previous = catalogChecks.get(sourceRouteId);
            if (isCoolingDown(previous, request.versionId())) {
                queueCatalogRefresh(sourceRouteId,
                    new RouteCatalogAndGeometryRefresher.CatalogRefreshRequest(request.versionId(), request.attempts(),
                        previous.nextCheck()));
                return;
            }

            refreshCatalog(sourceRouteId, request);
        } catch (RuntimeException failure) {
            LOG.warn(
                    "event=route_change_catalog_refresh_failed routeId={} routeVersionId={} failureType={}",
                    sourceRouteId,
                    request.versionId(),
                    failure.getClass().getSimpleName()
            );

            retryCatalogRefresh(sourceRouteId, request);
        }
    }

    private void refreshCatalog(String sourceRouteId, RouteCatalogAndGeometryRefresher.CatalogRefreshRequest request) {
        catalogChecks.put(sourceRouteId,
            new RouteCatalogAndGeometryRefresher.CatalogRefreshCooldown(request.versionId(),
                clock.instant().plus(mismatchCooldown)));

        if (catalog.refresh(sourceRouteId, OffsetDateTime.now(clock)).isEmpty()) {
            retryCatalogRefresh(sourceRouteId, request);
            return;
        }

        states.findBySourceId(sourceRouteId).ifPresent(this::requestMissingGeometry);
    }

    private void requestMissingGeometry(JdbcRouteRefreshStateRepository.State routeState) {
        if (coversGeometry(routeState) && !routeState.hasGeometry()) {
            requestVersion(routeState.routeId(),
                new RouteCatalogAndGeometryRefresher.GeometryRefreshRequest(
                    routeState.versionId(), 0, clock.instant()));
        }
    }

    private void refreshVersion(final long routeId, RouteCatalogAndGeometryRefresher.GeometryRefreshRequest request) {
        RouteCatalogAndGeometryRefresher.GeometryRefreshRequest activeRequest = request;
        try {
            Optional<JdbcRouteRefreshStateRepository.State> currentRouteState = states.findByRouteId(routeId);
            if (currentRouteState.isEmpty()) {
                return;
            }

            JdbcRouteRefreshStateRepository.State routeState = currentRouteState.get();
            if (routeState.versionId() != request.versionId()) {
                requestMissingGeometry(routeState);
                return;
            }

            if (!coversGeometry(routeState) || routeState.hasGeometry()) {
                return;
            }

            final int attemptNumber = reserveSourceAttempt(routeId, request.versionId());
            if (attemptNumber == 0) {
                return;
            }
            activeRequest = new RouteCatalogAndGeometryRefresher.GeometryRefreshRequest(
                request.versionId(), attemptNumber - 1, request.due());

            RefreshRouteGeometry.Outcome outcome =
                geometry.ensure(routeState.publicRouteId(), OffsetDateTime.now(clock));
            if (isFinished(outcome)) {
                return;
            }

            if (outcome == RefreshRouteGeometry.Outcome.REFERENCE_CHANGED) {
                Optional<JdbcRouteRefreshStateRepository.State> latestRouteState = states.findByRouteId(routeId);
                if (latestRouteState.isPresent() && latestRouteState.get().versionId() != request.versionId()) {
                    requestMissingGeometry(latestRouteState.get());
                    return;
                }
                retryVersion(routeId, activeRequest);
                return;
            }

            retryVersion(routeId, activeRequest);
        } catch (RuntimeException failure) {
            LOG.warn(
                    "event=route_change_geometry_refresh_failed routeId={} routeVersionId={} failureType={}",
                    routeId,
                    request.versionId(),
                    failure.getClass().getSimpleName()
            );

            retryVersion(routeId, activeRequest);
        }
    }

    private static boolean isFinished(RefreshRouteGeometry.Outcome outcome) {
        return switch (outcome) {
            case UPDATED, UNCHANGED, RECOVERY_SCHEDULED, RECOVERY_EXHAUSTED -> true;
            default -> false;
        };
    }

    private int reserveSourceAttempt(final long routeId, final long versionId) {
        RouteCatalogAndGeometryRefresher.GeometryRefreshAttempts previous = versionBudgets.get(routeId);
        if (isExhausted(previous, versionId)) {
            return 0;
        }

        int attempts = 1;
        if (previous != null && previous.versionId() == versionId) {
            attempts = previous.attempts() + 1;
        }

        versionBudgets.put(routeId, new RouteCatalogAndGeometryRefresher.GeometryRefreshAttempts(versionId, attempts));
        return attempts;
    }

    private static boolean isExhausted(
        RouteCatalogAndGeometryRefresher.GeometryRefreshAttempts previous,
        final long versionId
    ) {
        return previous != null && previous.versionId() == versionId && previous.attempts() >= MAX_ATTEMPTS;
    }

    private boolean coversGeometry(JdbcRouteRefreshStateRepository.State routeState) {
        if (!geometryProperties.enabled()) {
            return false;
        }

        String routeId = routeState.publicRouteId();
        if (inheritedCollectionScope) {
            routeId = routeState.sourceRouteId();
        }
        return geometryProperties.includeCatalog() || geometryRoutes.contains(routeId);
    }

    private void retryVersion(final long routeId, RouteCatalogAndGeometryRefresher.GeometryRefreshRequest request) {
        final int attempts = request.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            LOG.warn("event=route_change_geometry_refresh_exhausted routeId={} routeVersionId={}",
                routeId, request.versionId());
            return;
        }

        requestVersion(routeId,
            new RouteCatalogAndGeometryRefresher.GeometryRefreshRequest(request.versionId(), attempts,
                clock.instant().plus(retryDelay(attempts))));
    }

    private void retryCatalogRefresh(String routeId, RouteCatalogAndGeometryRefresher.CatalogRefreshRequest request) {
        final int attempts = request.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            catalogRequests.computeIfPresent(routeId, (id, pending) -> {
                if (pending.versionId() == request.versionId()) {
                    return null;
                }
                return pending;
            });
            LOG.warn("event=route_change_catalog_refresh_exhausted routeId={} routeVersionId={}",
                routeId, request.versionId());
            return;
        }

        Duration delay = retryDelay(attempts);
        if (delay.compareTo(mismatchCooldown) < 0) {
            delay = mismatchCooldown;
        }

        queueCatalogRefresh(routeId,
            new RouteCatalogAndGeometryRefresher.CatalogRefreshRequest(request.versionId(), attempts,
                clock.instant().plus(delay)));
    }

    private static Duration retryDelay(final int attempts) {
        return Duration.ofSeconds(Math.min(300, 10L << Math.min(attempts - 1, 5)));
    }

    private void requestVersion(final long routeId, RouteCatalogAndGeometryRefresher.GeometryRefreshRequest request) {
        versions.merge(routeId, request, RouteCatalogAndGeometryRefresher::later);
    }

    private void queueCatalogRefresh(String routeId, RouteCatalogAndGeometryRefresher.CatalogRefreshRequest request) {
        catalogRequests.merge(routeId, request, RouteCatalogAndGeometryRefresher::later);
    }

    private static <T extends RouteCatalogAndGeometryRefresher.RefreshRequest> T later(
        T existingRequest,
        T incomingRequest
    ) {
        if (existingRequest.versionId() != incomingRequest.versionId()) {
            if (existingRequest.versionId() > incomingRequest.versionId()) {
                return existingRequest;
            }
            return incomingRequest;
        }

        if (existingRequest.attempts() != incomingRequest.attempts()) {
            if (existingRequest.attempts() > incomingRequest.attempts()) {
                return existingRequest;
            }
            return incomingRequest;
        }

        if (existingRequest.due().isBefore(incomingRequest.due())) {
            return existingRequest;
        }
        return incomingRequest;
    }

    private <K, T extends RouteCatalogAndGeometryRefresher.RefreshRequest> Map.Entry<K, T> takeDue(Map<K, T> requests) {
        for (Map.Entry<K, T> entry : requests.entrySet()) {
            if (!entry.getValue().due().isAfter(clock.instant()) && requests.remove(entry.getKey(), entry.getValue())) {
                return Map.entry(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    private boolean hasDueWork() {
        return versions.values().stream().anyMatch(request -> !request.due().isAfter(clock.instant()))
                || catalogRequests.values().stream().anyMatch(request -> !request.due().isAfter(clock.instant()));
    }

    private interface RefreshRequest {

        long versionId();

        int attempts();

        Instant due();
    }

    private record GeometryRefreshRequest(
        long versionId,
        int attempts,
        Instant due
    ) implements RouteCatalogAndGeometryRefresher.RefreshRequest {

    }

    private record CatalogRefreshRequest(
        long versionId,
        int attempts,
        Instant due
    ) implements RouteCatalogAndGeometryRefresher.RefreshRequest {

    }

    private record CatalogRefreshCooldown(long versionId, Instant nextCheck) {

    }

    private record GeometryRefreshAttempts(long versionId, int attempts) {

    }
}
