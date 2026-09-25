package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 회수된 라벨로 셀 통계를 다시 내고 한 세대를 더한다.
 *
 * <p><b>예보와 다른 시계에서 돈다.</b> 한 배치에 묶으면 통계가 한 번 돌 때마다 예보가 멈춘다.
 * 통계는 관측이 쌓이면 바뀌고 예보는 판마다 나가서 급한 정도가 다르다.
 *
 * <p>옛 세대는 안 지운다. 밀린 batch 를 뒤늦게 처리할 때 그 관측 시각에 쓸 수 있었던 세대를
 * 골라야 같은 batch 가 같은 값을 낸다.
 *
 * <p>입력 조회부터 세대 저장까지 별도 writer가 노선별 transaction으로 묶는다.
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
    private final StopDemandStatisticsWriter writer;
    private final Clock clock;

    public StopDemandStatisticsJob(
        RouteVersionRepository routeVersionRepository,
        StopDemandStatisticsWriter writer,
        Clock clock
    ) {
        this.routeVersionRepository = routeVersionRepository;
        this.writer = writer;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${forecast.statistics-interval}")
    public void recomputeStopDemand() {
        Instant computedAt = clock.instant();
        for (Long routeVersionId : routeVersionRepository.findActiveVersionIds().stream().sorted().toList()) {
            writer.recompute(routeVersionId, computedAt);
        }
    }

}
