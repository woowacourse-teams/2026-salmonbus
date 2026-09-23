package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.domain.statistics.StopDemandAggregator;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.domain.statistics.StopDemandHourlyTotals;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 노선별로 입력 조회와 통계 저장을 한 트랜잭션으로 처리한다. */
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
    public void recompute(final long routeVersionId, Instant computedAt) {
        List<StopDemandHourlyTotals> totals = repository.readHourlyTotals(routeVersionId, computedAt);
        if (totals.isEmpty()) {
            return;
        }
        String calculationVersion = RefreshDemandStatisticsService.CURRENT_CALCULATION_VERSION;
        final int revision = Math.incrementExact(repository.currentRevision(routeVersionId, calculationVersion));
        repository.append(new DemandStatisticsVersion(routeVersionId, calculationVersion, revision,
            computedAt, computedAt, StopDemandAggregator.aggregate(totals, clock)));
    }
}
