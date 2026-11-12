package com.gustler.backend.forecasting.application.alighting;

import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.util.Map;

public interface AlightingAwareFeatureQuery {
    Map<Long, AlightingAwareFeatureSnapshot> read(long observationBatchId, RouteStops stops, AlightingAwareRouteStatistics statistics);
    default Map<Long, AlightingAwareFeatureSnapshot> readExtended(
        final long observationBatchId, RouteStops stops, AlightingAwareRouteStatistics statistics
    ) {
        return read(observationBatchId, stops, statistics);
    }
}
