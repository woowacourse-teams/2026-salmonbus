package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.quality.InvestigateTripQuality;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
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
