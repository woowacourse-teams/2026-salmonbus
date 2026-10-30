package com.gustler.backend.routeoperations.api;

import java.time.Instant;

/** Read-only evidence for an operator; requested ON is distinct from actual publication. */
public record RouteOperationPreview(
    String routeId,
    RouteOperation operation,
    String state,
    boolean workerFresh,
    boolean globalCollectionEnabled,
    boolean globalForecastEnabled,
    int maximumCollectionRoutes,
    long requestedCollectionRoutes,
    Instant workerReportedAt,
    Instant latestObservationAt,
    Instant latestPublicationAt) {
}
