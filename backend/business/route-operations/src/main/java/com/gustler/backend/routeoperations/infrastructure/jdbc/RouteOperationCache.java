package com.gustler.backend.routeoperations.infrastructure.jdbc;

import com.gustler.backend.routeoperations.api.RouteOperation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** 알림 연결의 상태가 확인된 스냅샷만 제공한다. 끊기거나 오래된 ON 상태는 반환하지 않는다. */
public final class RouteOperationCache {
    private static final long MAX_SILENCE_NANOS = Duration.ofSeconds(15).toNanos();

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final LongSupplier ticker;

    public RouteOperationCache() {
        this(System::nanoTime);
    }

    RouteOperationCache(LongSupplier ticker) {
        this.ticker = ticker;
    }

    public List<RouteOperation> list() {
        return current().operations();
    }

    public Optional<RouteOperation> find(String routeId) {
        RouteOperation.requireRouteId(routeId);
        return Optional.ofNullable(current().byRouteId().get(routeId));
    }

    void replaceAll(List<RouteOperation> operations) {
        Map<String, RouteOperation> byRouteId = new TreeMap<>();
        for (RouteOperation operation : operations) {
            byRouteId.put(operation.routeId(), operation);
        }
        snapshot.set(copyOf(byRouteId));
    }

    void replace(String routeId, Optional<RouteOperation> operation) {
        Map<String, RouteOperation> updated = new TreeMap<>(current().byRouteId());
        updated.remove(routeId);
        operation.ifPresent(value -> updated.put(routeId, value));
        snapshot.set(copyOf(updated));
    }

    void verified() {
        snapshot.updateAndGet(this::refreshVerification);
    }

    void invalidate() {
        snapshot.set(null);
    }

    private Snapshot refreshVerification(Snapshot current) {
        if (current == null) {
            return null;
        }
        return new Snapshot(current.byRouteId(), current.operations(), ticker.getAsLong());
    }

    private Snapshot copyOf(Map<String, RouteOperation> operations) {
        return new Snapshot(Map.copyOf(operations), List.copyOf(operations.values()), ticker.getAsLong());
    }

    private Snapshot current() {
        Snapshot current = snapshot.get();
        if (current == null) {
            throw new IllegalStateException("ROUTE_OPERATIONS_UNAVAILABLE");
        }
        if (ticker.getAsLong() - current.verifiedAtNanos() > MAX_SILENCE_NANOS) {
            throw new IllegalStateException("ROUTE_OPERATIONS_UNAVAILABLE");
        }
        return current;
    }

    private record Snapshot(
        Map<String, RouteOperation> byRouteId,
        List<RouteOperation> operations,
        long verifiedAtNanos
    ) {
    }
}
