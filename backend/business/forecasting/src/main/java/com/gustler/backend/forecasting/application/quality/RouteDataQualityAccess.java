package com.gustler.backend.forecasting.application.quality;

public interface RouteDataQualityAccess {
    /** 잠금 없이 현재 품질 버전을 읽는다. 행이 아직 없으면 최초 버전인 1이다. */
    long current(long routeVersionId);
    void ensureExists(long routeVersionId);
    long lock(long routeVersionId);
    long lockByRoute(long routeId);
    boolean anyInvestigationPending(long routeVersionId);
    boolean investigationPending(long routeVersionId, String vehicleId);
}
