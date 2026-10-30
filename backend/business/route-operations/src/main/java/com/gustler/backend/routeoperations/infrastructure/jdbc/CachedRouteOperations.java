package com.gustler.backend.routeoperations.infrastructure.jdbc;

import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperationPreview;
import com.gustler.backend.routeoperations.api.RouteOperations;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 일반 조회만 캐시한다. 변경과 발행 트랜잭션의 검사, 운영 진단은 DB에서 처리한다. */
public final class CachedRouteOperations implements RouteOperations {
    private final RouteOperations database;
    private final RouteOperationCache cache;

    public CachedRouteOperations(RouteOperations database, RouteOperationCache cache) {
        this.database = database;
        this.cache = cache;
    }

    @Override
    public List<RouteOperation> list() {
        return cache.list();
    }

    @Override
    public Optional<RouteOperation> find(String routeId) {
        return cache.find(routeId);
    }

    @Override
    public RouteOperation required(String routeId) {
        return find(routeId).orElseThrow(() -> new IllegalArgumentException("ROUTE_NOT_REGISTERED"));
    }

    @Override
    public RouteOperationPreview preview(String routeId) {
        return database.preview(routeId);
    }

    @Override
    public RouteOperation register(String routeId, UUID request, String actor) {
        return database.register(routeId, request, actor);
    }

    @Override
    public RouteOperation change(
        String routeId,
        final long expected,
        UUID request,
        String actor,
        String action,
        final boolean enabled,
        String reason,
        String message,
        String source
    ) {
        return database.change(routeId, expected, request, actor, action, enabled, reason, message, source);
    }

    @Override
    public void reportRuntime(final boolean collect, final boolean forecast, final int maximumRoutes) {
        database.reportRuntime(collect, forecast, maximumRoutes);
    }

    @Override
    public void registrationResult(String routeId, final long revision, final boolean ready) {
        database.registrationResult(routeId, revision, ready);
    }

    @Override
    public void geometryResult(String routeId, final long revision, final boolean ready) {
        database.geometryResult(routeId, revision, ready);
    }

    @Override
    public void modelResult(String routeId, final boolean supported) {
        database.modelResult(routeId, supported);
    }

    @Override
    public void acknowledge(String routeId, final long revision) {
        database.acknowledge(routeId, revision);
    }

    @Override
    public boolean allowPublication(final long routeVersion, Instant observedAt) {
        return database.allowPublication(routeVersion, observedAt);
    }

    @Override
    public void publicationCommitted(final long routeVersion, Instant observedAt) {
        database.publicationCommitted(routeVersion, observedAt);
    }

    @Override
    public boolean excluded(String routeId, Instant from, Instant until) {
        return database.excluded(routeId, from, until);
    }
}
