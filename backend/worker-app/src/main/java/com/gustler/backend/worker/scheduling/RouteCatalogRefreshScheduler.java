package com.gustler.backend.worker.scheduling;

import static com.gustler.backend.diagnostics.Logfmt.quote;

import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometryEvidence;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.RouteGeometryProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 기동과 매일 서울시각04시에 공통 근거를 먼저 갱신한 뒤 노선 정보를 순차 수집한다. */
@Component
@ConditionalOnExpression("${collection.enabled:false} or ${route-geometry.enabled:false}")
public class RouteCatalogRefreshScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteCatalogRefreshScheduler.class);
    private final CollectionProperties properties;
    private final RefreshRouteCatalog catalog;
    private final Clock clock;
    private final RefreshRouteGeometry geometry;
    private final Executor geometryExecutor;
    private final Optional<RefreshRouteGeometryEvidence> evidence;
    private final Set<String> geometryRoutes;
    private final boolean geometryEnabled;
    private final boolean includeCatalog;
    private final boolean inheritedCollectionScope;
    private final Optional<JdbcRouteRefreshStateRepository> states;
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object refreshMonitor = new Object();
    // No request means idle. One request retains its identity through submission retries.
    private RefreshRequest refreshRequest;

    private enum RefreshStage { REQUESTED, SUBMITTED }
    private static final class RefreshRequest {
        RefreshStage stage = RefreshStage.REQUESTED;
    }

    public RouteCatalogRefreshScheduler(
            CollectionProperties properties,
            RefreshRouteCatalog catalog,
            Clock clock,
            RefreshRouteGeometry geometry,
            Executor geometryExecutor,
            RouteGeometryProperties geometryProperties) {
        this(properties, catalog, clock, geometry, geometryExecutor, geometryProperties, Optional.empty());
    }

    public RouteCatalogRefreshScheduler(
            CollectionProperties properties,
            RefreshRouteCatalog catalog,
            Clock clock,
            RefreshRouteGeometry geometry,
            @Qualifier("routeGeometryExecutor") Executor geometryExecutor,
            RouteGeometryProperties geometryProperties,
            Optional<RefreshRouteGeometryEvidence> evidence) {
        this(properties, catalog, clock, geometry, geometryExecutor, geometryProperties, evidence, Optional.empty());
    }

    @Autowired
    public RouteCatalogRefreshScheduler(CollectionProperties properties, RefreshRouteCatalog catalog, Clock clock,
            RefreshRouteGeometry geometry, @Qualifier("routeGeometryExecutor") Executor geometryExecutor,
            RouteGeometryProperties geometryProperties, Optional<RefreshRouteGeometryEvidence> evidence,
            Optional<JdbcRouteRefreshStateRepository> states) {
        this.properties = properties;
        this.catalog = catalog;
        this.clock = clock;
        this.geometry = geometry;
        this.geometryExecutor = geometryExecutor;
        this.evidence = evidence;
        this.states = states;
        this.inheritedCollectionScope = geometryProperties.routeIds().isEmpty();
        this.geometryEnabled = geometryProperties.enabled();
        this.includeCatalog = geometryEnabled && geometryProperties.includeCatalog();
        this.geometryRoutes = geometryRouteIds(properties, geometryProperties);
    }

    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (started.compareAndSet(false, true)) refreshAllRoutes();
    }

    /** 전체 HTTP 작업은 전용 실행기에 맡기고, 대기·실행 중의 기동/정기 요청을 합친다. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul", scheduler = "collectionTaskScheduler")
    public void refreshAllRoutes() {
        if (observedRouteIds().isEmpty() && geometryRoutes.isEmpty() && !includeCatalog) return;
        synchronized (refreshMonitor) {
            if (refreshRequest == null) refreshRequest = new RefreshRequest();
        }
        retryQueuedRefresh();
    }

    /** 거절된 제출만 다시 시도한다. 새 수집 요청이나 외부 HTTP를 스케줄 스레드에서 만들지 않는다. */
    @Scheduled(fixedDelayString = "PT10S", scheduler = "collectionTaskScheduler")
    public void retryQueuedRefresh() {
        final RefreshRequest submitted;
        synchronized (refreshMonitor) {
            if (refreshRequest == null || refreshRequest.stage != RefreshStage.REQUESTED) return;
            submitted = refreshRequest;
            submitted.stage = RefreshStage.SUBMITTED;
        }
        // Keep executor invocation outside the monitor, including synchronous test executors.
        try {
            geometryExecutor.execute(() -> {
                try {
                    refreshEvidence();
                    refreshRoutes();
                } finally {
                    synchronized (refreshMonitor) {
                        if (refreshRequest == submitted) refreshRequest = null;
                    }
                }
            });
        } catch (RejectedExecutionException full) {
            synchronized (refreshMonitor) {
                if (refreshRequest == submitted) submitted.stage = RefreshStage.REQUESTED;
            }
            LOGGER.warn("event=route_geometry_refresh_deferred reason=QUEUE_FULL scope=CATALOG");
        }
    }

    private void refreshEvidence() {
        if (!geometryEnabled) return;
        evidence.ifPresent(source -> {
            try {
                source.refresh();
            } catch (RuntimeException failure) {
                // 제공자 실패 뒤에도 기존 유효 캐시 또는 원본으로 노선별 수집을 계속한다.
                LOGGER.warn("event=route_geometry_evidence_refresh_failed exceptionType={}",
                        quote(failure.getClass().getSimpleName()));
            }
        });
    }

    private void refreshRoutes() {
        Set<String> routes = new LinkedHashSet<>(observedRouteIds());
        Map<String, String> shapeTargets = new LinkedHashMap<>();
        for (String routeId : geometryRoutes) addShapeTarget(shapeTargets, routeId, inheritedCollectionScope);
        if (includeCatalog) {
            for (String sourceId : properties.routeIds()) addShapeTarget(shapeTargets, sourceId, true);
            try {
                for (String publicId : catalog.currentGbisRouteIds()) addShapeTarget(shapeTargets, publicId, false);
            } catch (RuntimeException failure) {
                LOGGER.warn("event=route_geometry_catalog_lookup_failed exceptionType={}",
                        quote(failure.getClass().getSimpleName()));
            }
        }
        routes.addAll(shapeTargets.keySet());
        for (String sourceId : routes) {
            if (refreshCatalog(sourceId) && shapeTargets.containsKey(sourceId))
                refreshGeometry(shapeTargets.get(sourceId));
        }
    }

    private void addShapeTarget(Map<String, String> targets, String routeId, boolean sourceId) {
        try {
            var state = states.flatMap(repository -> sourceId
                    ? repository.findBySourceId(routeId) : repository.findByPublicId(routeId));
            // New GBIS routes use their upstream ID as public ID until first catalog insertion.
            targets.put(state.map(JdbcRouteRefreshStateRepository.State::sourceRouteId).orElse(routeId),
                    state.map(JdbcRouteRefreshStateRepository.State::publicRouteId).orElse(routeId));
        } catch (RuntimeException failure) {
            LOGGER.warn("event=route_geometry_target_lookup_failed routeId={} exceptionType={}",
                    quote(routeId), quote(failure.getClass().getSimpleName()));
        }
    }

    private boolean refreshCatalog(String routeId) {
        try {
            if (catalog.refresh(routeId, OffsetDateTime.now(clock)).isPresent()) return true;
            LOGGER.warn("event=route_catalog_refresh_failed routeId={} routeName={} reason=UPSTREAM_OR_QUOTA",
                    quote(routeId), quote(knownRouteName(routeId)));
        } catch (RuntimeException failure) {
            // 인증키가 포함될 수 있는 HTTP 예외 본문은 기록하지 않는다.
            LOGGER.warn("event=route_catalog_refresh_failed routeId={} routeName={}"
                            + " reason=REFRESH_EXCEPTION exceptionType={}",
                    quote(routeId), quote(knownRouteName(routeId)), quote(failure.getClass().getSimpleName()));
        }
        return false;
    }

    private String knownRouteName(String routeId) {
        try {
            return catalog.knownRouteName(routeId);
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    private void refreshGeometry(String routeId) {
        try {
            Outcome outcome = geometry.refresh(routeId, OffsetDateTime.now(clock));
            LOGGER.info("event=route_geometry_refresh routeId={} outcome={}", quote(routeId), outcome);
        } catch (RuntimeException failure) {
            LOGGER.warn("event=route_geometry_refresh_failed routeId={} exceptionType={}",
                    quote(routeId), quote(failure.getClass().getSimpleName()));
        }
    }

    private static Set<String> geometryRouteIds(
            CollectionProperties collectionProperties, RouteGeometryProperties geometryProperties) {
        if (!geometryProperties.enabled()) return Set.of();
        List<String> routeIds = geometryProperties.routeIds();
        if (routeIds.isEmpty()) routeIds = collectionProperties.routeIds();
        return Collections.unmodifiableSet(new LinkedHashSet<>(routeIds));
    }

    private List<String> observedRouteIds() {
        return properties.enabled() ? properties.routeIds() : List.of();
    }
}
