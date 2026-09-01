package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 회수된 라벨로 셀 통계를 다시 내고 한 세대를 덮어쓴다.
 *
 * <p><b>예보와 다른 시계에서 돈다.</b> 한 배치에 묶으면 통계가 한 번 돌 때마다 예보가 멈춘다.
 * 통계는 관측이 쌓이면 바뀌고 예보는 판마다 나가서 급한 정도가 다르다.
 *
 * <p>한 세대를 덮어쓰는 것은 포트 구현이 한 transaction 으로 한다. 여기서 걸면 자기 메서드 호출이라
 * 프록시를 안 거쳐 transaction 이 안 열린다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class StopDemandStatisticsJob {

    /**
     * 셀 값을 어떤 규칙으로 만들었는지에 붙인 이름.
     *
     * <p>자리가 찬 비율도 순승차 비율도 정원으로 나눈 값이라, 정원을 유도하는 규칙이 바뀌면 값의 뜻이
     * 달라진다. 그래서 이 이름이 키에 들어가고 옛 규칙으로 낸 행과 안 섞인다.
     *
     * <p>지금 규칙은 <b>그 차량이 보여 준 최대 잔여석</b>이다. 종전 구현의 47석 고정은 오류였다.
     * 두 노선 배속 차량 어디에도 없던 값이고 실측 최빈값은 44석이다.
     *
     * <p>계수 번들이 오면 그쪽 특징 계약이 정한 이름과 규칙을 쓴다. 두 이름이 다르면 서빙이 이 행을
     * 안 읽으므로, 번들이 올 때 집계를 그 규칙으로 다시 돌려야 한다.
     */
    public static final String CURRENT_CALCULATION_VERSION = "observed-max-capacity-v1";

    private final RouteVersionRepository routeVersionRepository;
    private final StopDemandStatisticsRepository stopDemandStatisticsRepository;
    private final Clock clock;

    public StopDemandStatisticsJob(
        RouteVersionRepository routeVersionRepository,
        StopDemandStatisticsRepository stopDemandStatisticsRepository,
        Clock clock
    ) {
        this.routeVersionRepository = routeVersionRepository;
        this.stopDemandStatisticsRepository = stopDemandStatisticsRepository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${forecast.statistics-interval}")
    public void recomputeStopDemand() {
        Instant computedAt = clock.instant();
        for (Long routeVersionId : routeVersionRepository.findActiveVersionIds()) {
            recomputeStopDemandOf(routeVersionId, computedAt);
        }
    }

    /**
     * 회수된 라벨이 하나도 없으면 세대를 안 올린다.
     *
     * <p>빈 세대를 남기면 그 세대를 읽은 예보가 이웃 폴백만 돌았다는 사실이 안 남고,
     * 세대 번호만 올라 채점이 헛되이 갈린다. 개편 직후 새 판본이 이 자리다.
     */
    private void recomputeStopDemandOf(
        final long routeVersionId,
        Instant computedAt
    ) {
        List<StopDemandHourlyTotals> hourlyTotals =
            stopDemandStatisticsRepository.readHourlyTotals(routeVersionId, computedAt);
        if (hourlyTotals.isEmpty()) {
            return;
        }
        final int nextRevision = stopDemandStatisticsRepository.currentRevision(
            routeVersionId, CURRENT_CALCULATION_VERSION) + 1;
        stopDemandStatisticsRepository.append(new StopDemandGeneration(
            routeVersionId,
            CURRENT_CALCULATION_VERSION,
            nextRevision,
            computedAt,
            computedAt,
            StopDemandAggregator.aggregate(hourlyTotals, clock)));
    }
}
