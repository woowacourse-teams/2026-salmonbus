package com.gustler.backend.forecasting.application.quality;

public interface DemandStatisticsRebuildTrigger {

    void requestVehicle(long routeVersionId, String vehicleId);

    void requestRoute(long routeVersionId);
}
