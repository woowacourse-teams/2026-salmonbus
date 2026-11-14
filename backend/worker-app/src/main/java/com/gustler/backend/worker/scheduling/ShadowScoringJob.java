package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.pipeline.CompareShadowScoring;
import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 그림자 채점의 주기 작업이다. 그림자 채점이 켜졌을 때만 등록된다.
 *
 * <ul>
 *   <li>기동이 끝나면 최근 예보가 있는 노선을 모두 한 번 되감게 한다.</li>
 *   <li>{@code forecast.scoring-flush-interval}(기본 5초)마다 마감 정리를 채점 줄에 넣고, 모아 둔 결과를 한 트랜잭션으로 쓴다.</li>
 *   <li>{@code forecast.scoring-comparison-interval}(기본 10분)마다 새 채점 결과를 옛 정산 결과와 대조한다.</li>
 * </ul>
 * 두 주기 작업은 전용 스케줄러({@code scoringTaskScheduler}) 한 스레드에서 돈다. 실패는 로그로만 남기고 다음 회차에 다시 한다.
 */
public class ShadowScoringJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowScoringJob.class);
    private final ShadowScoringCycle cycle;
    private final CompareShadowScoring comparison;

    public ShadowScoringJob(ShadowScoringCycle cycle, CompareShadowScoring comparison) {
        this.cycle = cycle;
        this.comparison = comparison;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!cycle.start()) {
            LOGGER.warn("event=pipeline_scoring_start_rejected");
        }
    }

    @Scheduled(initialDelayString = "${forecast.scoring-flush-interval:5s}",
        fixedDelayString = "${forecast.scoring-flush-interval:5s}", scheduler = "scoringTaskScheduler")
    public void flush() {
        cycle.tick();
        cycle.flush();
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
