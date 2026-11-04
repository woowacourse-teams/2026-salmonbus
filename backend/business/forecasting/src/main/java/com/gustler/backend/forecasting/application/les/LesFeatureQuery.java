package com.gustler.backend.forecasting.application.les;

import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.les.LesFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
import java.util.Map;

public interface LesFeatureQuery {
    Map<Long, LesFeatureSnapshot> read(long observationBatchId, RouteStops stops, LesRouteStatistics statistics);
}
