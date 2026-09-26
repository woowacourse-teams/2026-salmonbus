package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.evaluation.EvaluateForecasts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ArrivalLabelJob {
    private final EvaluateForecasts service;

    public ArrivalLabelJob(EvaluateForecasts service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${forecast.settlement-interval}")
    public void settleArrivalLabels() {
        service.settleArrivalLabels();
    }
}
