package com.gustler.backend.forecasting.domain.quality;

public interface RouteDataQualityRepository {
    RouteDataQuality findForUpdate(long routeVersionId);
    void save(RouteDataQuality quality);
}
