package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.pipeline.CompareShadowScoring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 새 채점 결과를 옛 정산 결과와 대조하는 주기 작업이다. 그림자 예보가 사건을 내는 {@code forecast.pipeline=v1}에서 채점이 켜졌을
 * 때만 등록된다. {@code pipeline=v2}에서는 옛 예측이 더 생기지 않아 돌리지 않는다.
 *
 * <p>{@code forecast.scoring-comparison-interval}(기본 10분)마다 채점 전용 스케줄러({@code scoringTaskScheduler})에서 돈다. 실패는
 * 로그로만 남기고 다음 회차에 다시 한다.
 */
public class ScoringComparisonJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScoringComparisonJob.class);
    private final CompareShadowScoring comparison;

    public ScoringComparisonJob(CompareShadowScoring comparison) {
        this.comparison = comparison;
    }

    @Scheduled(initialDelayString = "${forecast.scoring-comparison-interval:10m}",
        fixedDelayString = "${forecast.scoring-comparison-interval:10m}", scheduler = "scoringTaskScheduler")
    public void compare() {
        try {
            comparison.compareRecent();
        } catch (RuntimeException failure) {
            LOGGER.warn("event=pipeline_scoring_comparison_failed exceptionType={}",
                failure.getClass().getSimpleName(), failure);
        }
    }
}
