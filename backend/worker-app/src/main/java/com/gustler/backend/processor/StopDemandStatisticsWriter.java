package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 노선 하나의 입력 조회부터 세대 저장까지 묶어, 판정 변경과 통계 계산이 섞이지 않게 한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class StopDemandStatisticsWriter {
    private final StopDemandStatisticsRepository repository;
    private final Clock clock;

    public StopDemandStatisticsWriter(StopDemandStatisticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void recompute(long routeVersionId, Instant computedAt) {
        List<StopDemandHourlyTotals> totals = repository.readHourlyTotals(routeVersionId, computedAt);
        if (totals.isEmpty()) {
            return;
        }
        String calculationVersion = StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION;
        int revision = repository.currentRevision(routeVersionId, calculationVersion) + 1;
        repository.append(new StopDemandGeneration(routeVersionId, calculationVersion, revision,
            computedAt, computedAt, StopDemandAggregator.aggregate(totals, clock)));
    }
}
