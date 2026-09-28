package com.gustler.backend.forecasting.application.quality;

public interface RouteDataQualityAccess {
    long lock(long routeVersionId);
    long lockByRoute(long routeId);
    boolean anyInvestigationPending(long routeVersionId);
    boolean investigationPending(long routeVersionId, String vehicleId);
}
