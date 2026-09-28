package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.evaluation.InitializeSameDayOutcomes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class SameDayOutcomesInitializationJob {
    private final InitializeSameDayOutcomes service;

    public SameDayOutcomesInitializationJob(InitializeSameDayOutcomes service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${forecast.same-day-initialization.interval:10s}")
    public void initializeNext() {
        service.initializeNext();
    }
}
