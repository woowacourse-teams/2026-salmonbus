package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.api.statistics.RefreshDemandStatistics;

import com.gustler.backend.forecasting.domain.publication.RouteVersionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 활성 노선의 통계를 갱신한다. 각 노선의 계산과 저장은 별도 트랜잭션으로 처리한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class RefreshDemandStatisticsService implements RefreshDemandStatistics {

    private static final Logger log = LoggerFactory.getLogger(RefreshDemandStatisticsService.class);

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

    /** 이전 전체 조회의 비교 검증용 진입점. 운영 scheduler에는 등록하지 않는다. */
    public void recomputeStopDemand() {
        Instant computedAt = clock.instant();
        String computedAtLocal = computedAt.atZone(clock.getZone())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        for (Long routeVersionId : routeVersionRepository.findActiveVersionIds().stream().sorted().toList()) {
            long startedAt = System.nanoTime();
            log.info("event=stop_demand_statistics status=STARTED routeVersionId={} computedAt={}",
                routeVersionId, computedAtLocal);
            try {
                // 이 job은 transaction을 열지 않는다. writer 프록시가 commit을 마친 뒤 반환한다.
                writer.recompute(routeVersionId, computedAt);
            } catch (RuntimeException exception) {
                log.error("event=stop_demand_statistics status=FAILED routeVersionId={} computedAt={} durationMs={} exceptionType={}",
                    routeVersionId, computedAtLocal, elapsedMillis(startedAt), exception.getClass().getSimpleName());
                throw exception;
            }
            // 입력이 없어 세대를 저장하지 않은 정상 반환도 COMPLETED에 포함한다.
            log.info("event=stop_demand_statistics status=COMPLETED routeVersionId={} computedAt={} durationMs={}",
                routeVersionId, computedAtLocal, elapsedMillis(startedAt));
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

}
