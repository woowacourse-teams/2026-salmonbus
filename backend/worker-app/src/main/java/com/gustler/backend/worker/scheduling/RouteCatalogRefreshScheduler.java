package com.gustler.backend.worker.scheduling;

import static com.gustler.backend.diagnostics.Logfmt.quote;

import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperations;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.RouteGeometryProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 수집과 같은 단일 스레드에서 최초 1회 및 완료 후 24시간마다 순차 확인한다. */
@Component
@ConditionalOnExpression("${collection.enabled:false} or ${route-geometry.enabled:false}")
public class RouteCatalogRefreshScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteCatalogRefreshScheduler.class);
    private final CollectionProperties properties;
    private final RefreshRouteCatalog catalog;
    private final Clock clock;
    private final RefreshRouteGeometry geometry;
    private final Executor geometryExecutor;
    private final Set<String> geometryRoutes;
    private final boolean includeCatalog;
    private final AtomicBoolean catalogGeometryPending = new AtomicBoolean();

    private RouteOperations operations;

    public RouteCatalogRefreshScheduler(
        CollectionProperties properties,
        RefreshRouteCatalog catalog,
        Clock clock,
        RefreshRouteGeometry geometry,
        @Qualifier("routeGeometryExecutor") Executor geometryExecutor,
        RouteGeometryProperties geometryProperties
    ) {
        this.properties = properties;
        this.catalog = catalog;
        this.clock = clock;
        this.geometry = geometry;
        this.geometryExecutor = geometryExecutor;
        this.includeCatalog = geometryProperties.enabled() && geometryProperties.includeCatalog();
        this.geometryRoutes = geometryRouteIds(properties, geometryProperties);
    }

    /**
     * 설정된 노선의 카탈로그와 형상을 갱신한다. 노선별 실패를 격리하고 중복 형상 작업은 합친다.
     */
    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT24H", scheduler = "collectionTaskScheduler")
    public void refreshAllRoutes() {
        Set<String> observed = new LinkedHashSet<>(observedRouteIds());
        Set<String> refreshed = new LinkedHashSet<>();
        for (String routeId : observed) {
            try {
                if (catalog.refresh(routeId, OffsetDateTime.now(clock)).isEmpty()) {
                    LOGGER.warn("event=route_catalog_refresh_failed routeId={} routeName={} reason=UPSTREAM_OR_QUOTA",
                        quote(routeId), quote(catalog.knownRouteName(routeId)));
                    continue;
                }
                refreshed.add(routeId);
                if (!includeCatalog && (geometryRoutes.contains(routeId) || operations != null)) {
                    enqueueGeometry(routeId, false);
                }
            } catch (RuntimeException failure) {
                // 인증키가 포함될 수 있는 HTTP 예외 본문은 기록하지 않는다.
                LOGGER.warn(
                    "event=route_catalog_refresh_failed routeId={} routeName={} reason=REFRESH_EXCEPTION exceptionType={}",
                    quote(routeId), quote(catalog.knownRouteName(routeId)), quote(failure.getClass().getSimpleName()));
            }
        }
        if (includeCatalog) {
            enqueueCatalogGeometry(observed, refreshed);
            return;
        }
        // 지도 전용 노선의 기본정보·정류장 HTTP도 기존 차량 관측 스케줄 밖에서 처리한다.
        geometryRoutes.stream()
            .filter(routeId -> !observed.contains(routeId))
            .forEach(routeId -> enqueueGeometry(routeId, true));
    }

    private void enqueueCatalogGeometry(Set<String> observed, Set<String> refreshed) {
        // 노선 수만큼 큐를 점유하지 않는다. 진행 중인 전체 갱신도 중복해서 쌓지 않는다.
        if (!catalogGeometryPending.compareAndSet(false, true)) {
            return;
        }
        try {
            geometryExecutor.execute(
                () -> {
                    try {
                        Set<String> routes = new LinkedHashSet<>(geometryRoutes);
                        routes.addAll(legacyOrObservedRouteIds());
                        try {
                            routes.addAll(catalog.currentGbisRouteIds());
                        } catch (RuntimeException failure) {
                            LOGGER.warn(
                                "event=route_geometry_catalog_lookup_failed exceptionType={}",
                                quote(failure.getClass().getSimpleName()));
                        }
                        for (String routeId : routes) {
                            if (!observed.contains(routeId)) {
                                refreshGeometryCatalog(routeId);
                                continue;
                            }
                            if (refreshed.contains(routeId)) {
                                refreshGeometry(routeId);
                            }
                        }
                    } finally {
                        catalogGeometryPending.set(false);
                        }
                });
        } catch (RejectedExecutionException full) {
            catalogGeometryPending.set(false);
            LOGGER.warn("event=route_geometry_refresh_deferred reason=QUEUE_FULL scope=CATALOG");
        }
    }

    private void enqueueGeometry(String routeId, final boolean refreshCatalog) {
        try {
            geometryExecutor.execute(() -> {
                if (refreshCatalog) {
                    refreshGeometryCatalog(routeId);
                    return;
                }
                refreshGeometry(routeId);
            });
        }
        catch (RejectedExecutionException full) {
            LOGGER.warn("event=route_geometry_refresh_deferred routeId={} reason=QUEUE_FULL", quote(routeId));
        }
    }

    private void refreshGeometryCatalog(String routeId) {
        try {
            if (catalog.refresh(routeId, OffsetDateTime.now(clock)).isPresent()) {
                refreshGeometry(routeId);
                return;
            }
            LOGGER.warn("event=route_geometry_refresh_deferred routeId={} reason=CATALOG_UNAVAILABLE", quote(routeId));
        } catch (RuntimeException failure) {
            LOGGER.warn("event=route_geometry_refresh_failed routeId={} exceptionType={}",
                quote(routeId), quote(failure.getClass().getSimpleName()));
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
        CollectionProperties collectionProperties,
        RouteGeometryProperties geometryProperties
    ) {
        if (!geometryProperties.enabled()) {
            return Set.of();
        }
        List<String> routeIds = geometryProperties.routeIds();
        if (routeIds.isEmpty()) {
            routeIds = collectionProperties.routeIds();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(routeIds));
    }

    @Autowired(required = false)
    public void setOperations(RouteOperations operations) {
        this.operations = operations;
    }

    private List<String> observedRouteIds() {
        if (operations != null) {
            return operations.list().stream()
                .filter(RouteOperation::canCollect)
                .map(RouteOperation::routeId)
                .toList();
        }
        if (properties.enabled()) {
            return properties.routeIds();
        }
        return List.of();
    }

    private List<String> legacyOrObservedRouteIds() {
        if (operations == null) {
            return properties.routeIds();
        }
        return observedRouteIds();
    }
}
