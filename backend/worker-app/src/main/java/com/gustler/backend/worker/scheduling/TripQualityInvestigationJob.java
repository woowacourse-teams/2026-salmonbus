package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.quality.InvestigateTripQuality;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 기존 Worker 스케줄에서 대기 중인 차량 품질 조사 회차를 실행한다.
 */
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
