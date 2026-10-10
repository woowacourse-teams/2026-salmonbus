package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.evaluation.AdvanceEvaluationArchive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="forecast.archive", name="enabled", havingValue="true")
public class EvaluationArchiveJob {
    private final AdvanceEvaluationArchive service;

    public EvaluationArchiveJob(AdvanceEvaluationArchive service) { this.service = service; }

    @Scheduled(fixedDelayString="${forecast.archive.interval:5s}", initialDelayString="${forecast.archive.initial-delay:60s}",
        scheduler="calibrationTaskScheduler")
    public void advance() { service.advance(); }
}
