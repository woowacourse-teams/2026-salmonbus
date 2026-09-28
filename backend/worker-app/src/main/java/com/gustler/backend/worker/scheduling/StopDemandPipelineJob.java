package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.statistics.AdvanceDemandStatistics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class StopDemandPipelineJob {
    private final AdvanceDemandStatistics service;

    public StopDemandPipelineJob(AdvanceDemandStatistics service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${forecast.statistics-step-interval:100ms}")
    public void advance() {
        service.advance();
    }
}
