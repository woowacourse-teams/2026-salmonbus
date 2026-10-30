package com.gustler.backend.routeoperations.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public runtime controls; internal database entities do not cross module boundaries. */
public interface RouteOperations {
    List<RouteOperation> list();

    Optional<RouteOperation> find(String routeId);

    RouteOperation required(String routeId);

    RouteOperationPreview preview(String routeId);

    RouteOperation register(String routeId, UUID request, String actor);

    RouteOperation change(
        String routeId,
        final long expected,
        UUID request,
        String actor,
        String action,
        final boolean enabled,
        String reason,
        String message,
        String source
    );

    void reportRuntime(final boolean collect, final boolean forecast, final int maximumRoutes);

    void registrationResult(String routeId, final long revision, final boolean ready);

    void geometryResult(String routeId, final long revision, final boolean ready);

    void modelResult(String routeId, final boolean supported);

    void acknowledge(String routeId, final long revision);

    boolean allowPublication(final long routeVersion, Instant observedAt);

    void publicationCommitted(final long routeVersion, Instant observedAt);

    boolean excluded(String routeId, Instant from, Instant until);
}
