package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.statistics.RefreshDemandStatistics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class StopDemandStatisticsJob {
    private final RefreshDemandStatistics service;

    public StopDemandStatisticsJob(RefreshDemandStatistics service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${forecast.statistics-interval}")
    public void recomputeStopDemand() {
        service.recomputeStopDemand();
    }
}
