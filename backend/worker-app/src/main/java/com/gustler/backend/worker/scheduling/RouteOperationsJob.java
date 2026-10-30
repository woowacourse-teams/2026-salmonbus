package com.gustler.backend.worker.scheduling;

import com.gustler.backend.observations.api.CollectionTiming;
import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperations;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.GbisProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 기존 수집 실행기에서 회차당 한 노선의 등록 또는 형상 갱신을 처리한다. */
@Component
@ConditionalOnExpression(
    "${route-operations.enabled:false} and (${collection.enabled:false} or"
        + " ${route-geometry.enabled:false})")
public class RouteOperationsJob {
    private static final Logger LOG = LoggerFactory.getLogger(RouteOperationsJob.class);

    private final RouteOperations operations;
    private final RefreshRouteCatalog catalog;
    private final RefreshRouteGeometry geometry;
    private final CollectionProperties collectionProperties;
    private final GbisProperties gbisProperties;
    private final Clock clock;
    private final boolean forecastEnabled;

    public RouteOperationsJob(
        RouteOperations operations,
        RefreshRouteCatalog catalog,
        RefreshRouteGeometry geometry,
        CollectionProperties collectionProperties,
        GbisProperties gbisProperties,
        Clock clock,
        @Value("${forecast.enabled:false}") final boolean forecastEnabled
    ) {
        this.operations = operations;
        this.catalog = catalog;
        this.geometry = geometry;
        this.collectionProperties = collectionProperties;
        this.gbisProperties = gbisProperties;
        this.clock = clock;
        this.forecastEnabled = forecastEnabled;
    }

    @Scheduled(fixedDelayString = "PT10S", scheduler = "collectionTaskScheduler")
    public void reconcile() {
        final int daily =
            gbisProperties.serviceKeys().stream().mapToInt(key -> gbisProperties.dailyLimitOf(key.alias())).sum();
        // 카탈로그와 형상, 도착 정보에 10%를 남긴다. 실제 호출 한도는 예약 단계에서 다시 검사한다.
        operations.reportRuntime(
            collectionProperties.enabled(), forecastEnabled, (int) ((daily * 0.9) / Math.max(1, dailyCalls())));
        boolean refreshed = false;
        for (RouteOperation operation : operations.list()) {
            refreshed = refreshOne(operation, refreshed);
            if (needsInactiveAcknowledgement(operation)) {
                operations.acknowledge(operation.routeId(), operation.revision());
            }
        }
    }

    private long dailyCalls() {
        if (collectionProperties.fixedInterval() == null) {
            return CollectionTiming.dailyCallsFor(1);
        }
        return 86400 / collectionProperties.fixedInterval().toSeconds() + 1;
    }

    private boolean refreshOne(RouteOperation operation, final boolean refreshed) {
        if (refreshed) {
            return true;
        }
        if (operation.registrationState().equals("PENDING")) {
            refreshRegistration(operation);
            return true;
        }
        if (needsGeometry(operation)) {
            refreshGeometry(operation.routeId(), operation.revision());
            return true;
        }
        return false;
    }

    private boolean needsInactiveAcknowledgement(RouteOperation operation) {
        return operation.appliedRevision() != operation.revision()
            && !operation.canCollect()
            && !operation.serviceRequested();
    }

    private boolean needsGeometry(RouteOperation operation) {
        return operation.registrationState().equals("READY")
            && operation.geometryState().equals("PENDING");
    }

    private void refreshRegistration(RouteOperation operation) {
        try {
            final boolean ready =
                catalog.refresh(operation.routeId(), OffsetDateTime.now(clock)).isPresent();
            operations.registrationResult(operation.routeId(), operation.revision(), ready);
            if (ready) {
                refreshGeometry(operation.routeId(), operation.revision());
            }
        } catch (RuntimeException failed) {
            operations.registrationResult(operation.routeId(), operation.revision(), false);
            LOG.warn(
                "event=route_registration_failed routeId={} exceptionType={}",
                operation.routeId(),
                failed.getClass().getSimpleName());
        }
    }

    private void refreshGeometry(String routeId, final long revision) {
        try {
            RefreshRouteGeometry.Outcome outcome = geometry.refresh(routeId, OffsetDateTime.now(clock));
            operations.geometryResult(
                routeId,
                revision,
                outcome == RefreshRouteGeometry.Outcome.UPDATED
                    || outcome == RefreshRouteGeometry.Outcome.UNCHANGED);
        } catch (RuntimeException failed) {
            operations.geometryResult(routeId, revision, false);
            LOG.warn("event=route_registration_geometry_deferred routeId={}", routeId);
        }
    }
}
