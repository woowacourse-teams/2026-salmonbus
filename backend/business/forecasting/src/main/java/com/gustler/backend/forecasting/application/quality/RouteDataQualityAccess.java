package com.gustler.backend.forecasting.application.quality;

public interface RouteDataQualityAccess {
    long lock(long routeVersionId);
    long lockByRoute(long routeId);
    void invalidate(long routeVersionId);
}
