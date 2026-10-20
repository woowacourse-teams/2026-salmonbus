package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.domain.quality.RouteDataQuality;
import com.gustler.backend.forecasting.domain.quality.RouteDataQualityRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 관측·편도 판정이 바뀐 노선의 품질 판본과 재집계 요청을 갱신해 이전 메모리 자료의 재사용을 막는다.
 */
@Service
public class RouteDataQualityChanges {
    private final RouteDataQualityRepository qualities;

    private final DemandStatisticsRebuildTrigger statistics;
    private WorkerQualityTransactions memory;

    public RouteDataQualityChanges(RouteDataQualityRepository qualities,
        DemandStatisticsRebuildTrigger statistics) {
        this.qualities = qualities;
        this.statistics = statistics;
    }

    @Autowired(required = false)
    public void useQualityMemory(WorkerQualityTransactions memory) {
        this.memory = memory;
    }

    public void vehiclesChanged(final long version, List<String> vehicles) {
        if (vehicles.isEmpty()) {
            return;
        }
        changeEligibility(version);
        vehicles.forEach(vehicle -> statistics.requestVehicle(version, vehicle));
    }

    public void routeChanged(final long version) {
        changeEligibility(version);
        statistics.requestRoute(version);
    }

    private void changeEligibility(final long version) {
        if (memory != null) {
            memory.changing();
        }
        RouteDataQuality quality = qualities.findForUpdate(version);
        quality.changeEligibility();
        qualities.save(quality);
    }
}
