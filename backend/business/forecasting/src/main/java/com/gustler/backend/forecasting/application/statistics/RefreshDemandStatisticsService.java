package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.api.statistics.RefreshDemandStatistics;

import com.gustler.backend.forecasting.domain.publication.RouteVersionRepository;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 활성 노선의 통계를 갱신한다. 각 노선의 계산과 저장은 별도 트랜잭션으로 처리한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class RefreshDemandStatisticsService implements RefreshDemandStatistics {

    /** 차량별 최대 잔여석을 정원으로 사용하는 계산 규칙의 버전. */
    public static final String CURRENT_CALCULATION_VERSION = "observed-max-capacity-v1";

    private final RouteVersionRepository routeVersionRepository;
    private final StopDemandStatisticsWriter writer;
    private final Clock clock;

    public RefreshDemandStatisticsService(
        RouteVersionRepository routeVersionRepository,
        StopDemandStatisticsWriter writer,
        Clock clock
    ) {
        this.routeVersionRepository = routeVersionRepository;
        this.writer = writer;
        this.clock = clock;
    }

    public void recomputeStopDemand() {
        Instant computedAt = clock.instant();
        for (Long routeVersionId : routeVersionRepository.findActiveVersionIds().stream().sorted().toList()) {
            writer.recompute(routeVersionId, computedAt);
        }
    }

}
