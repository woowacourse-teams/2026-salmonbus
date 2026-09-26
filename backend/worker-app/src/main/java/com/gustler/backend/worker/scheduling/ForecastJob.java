package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.publication.PublishPendingForecasts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastJob {
    private final PublishPendingForecasts service;

    public ForecastJob(PublishPendingForecasts service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${forecast.interval}")
    public void writeForecasts() {
        service.writeForecasts();
    }
}
