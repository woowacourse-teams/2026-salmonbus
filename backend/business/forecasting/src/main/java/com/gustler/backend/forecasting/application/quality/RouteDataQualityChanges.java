package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.domain.quality.RouteDataQuality;
import com.gustler.backend.forecasting.domain.quality.RouteDataQualityRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RouteDataQualityChanges {
    private final RouteDataQualityRepository qualities;
    private final DemandStatisticsRebuildTrigger statistics;

    public RouteDataQualityChanges(final RouteDataQualityRepository qualities,
        final DemandStatisticsRebuildTrigger statistics) {
        this.qualities = qualities;
        this.statistics = statistics;
    }

    public void vehiclesChanged(final long version, final List<String> vehicles) {
        if (vehicles.isEmpty()) { return; }
        changeEligibility(version);
        vehicles.forEach(vehicle -> statistics.requestVehicle(version, vehicle));
    }

    public void routeChanged(final long version) {
        changeEligibility(version);
        statistics.requestRoute(version);
    }

    private void changeEligibility(final long version) {
        final RouteDataQuality quality = qualities.findForUpdate(version);
        quality.changeEligibility();
        qualities.save(quality);
    }
}
