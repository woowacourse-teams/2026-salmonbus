package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.evaluation.AdvanceEvaluationArchive;
import com.gustler.backend.forecasting.api.evaluation.LesEvaluationArchive;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 기존 Worker 스케줄에서 정책이 허용한 정산 이관 회차를 실행한다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast.archive", name = "enabled", havingValue = "true")
public class EvaluationArchiveJob {
    private final AdvanceEvaluationArchive service;
    private LesEvaluationArchive les;

    @Autowired(required = false)
    public void useLesArchive(LesEvaluationArchive les) {
        this.les = les;
    }

    public EvaluationArchiveJob(AdvanceEvaluationArchive service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${forecast.archive.interval:5s}",
        initialDelayString = "${forecast.archive.initial-delay:60s}",
        scheduler = "calibrationTaskScheduler")
    public void advance() {
        service.advance();
        if (les != null) {
            les.advance();
        }
    }
}
