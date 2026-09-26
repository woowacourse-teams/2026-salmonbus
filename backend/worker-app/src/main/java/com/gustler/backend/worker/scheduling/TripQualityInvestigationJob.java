package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.quality.InvestigateTripQuality;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("${forecast.quality-enabled:${forecast.enabled:false} or ${collection.enabled:false}}")
public class TripQualityInvestigationJob {
    private final InvestigateTripQuality service;

    public TripQualityInvestigationJob(InvestigateTripQuality service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${forecast.quality-investigation-interval:10s}")
    public void investigate() {
        service.investigate();
    }
}
