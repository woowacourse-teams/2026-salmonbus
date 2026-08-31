package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판 하나의 예보를 쓰고 그 판을 닫는다.
 *
 * <p>배치와 따로 둔 이유는 <b>한 판이 한 transaction</b> 이어야 하기 때문이다. 한 회차에 판을
 * 스무 개까지 보는데 그것을 한 transaction 에 묶으면 한 판이 실패할 때 앞의 열아홉이 같이 되돌아가고,
 * 행 수도 노선당 수만 줄이 된다. 같은 빈 안에서 자기 메서드를 부르면 프록시를 안 거쳐
 * transaction 이 안 걸리므로 빈을 갈랐다.
 *
 * <p>한 판의 행은 전부 같은 배포와 같은 계산 시각을 갖는다. 그 셋이 "한 판은 같은 판에서
 * 같은 배포로 나온다"는 불변식을 만든다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastBatchWriter {

    private final VehicleTrajectoryRepository vehicleTrajectoryRepository;
    private final SeatForecastRepository seatForecastRepository;
    private final StopDemandStatisticsRepository stopDemandStatisticsRepository;
    private final Clock clock;

    public ForecastBatchWriter(
        VehicleTrajectoryRepository vehicleTrajectoryRepository,
        SeatForecastRepository seatForecastRepository,
        StopDemandStatisticsRepository stopDemandStatisticsRepository,
        Clock clock
    ) {
        this.vehicleTrajectoryRepository = vehicleTrajectoryRepository;
        this.seatForecastRepository = seatForecastRepository;
        this.stopDemandStatisticsRepository = stopDemandStatisticsRepository;
        this.clock = clock;
    }

    @Transactional
    public void writeForecastsOf(
        PendingForecastBatch batch,
        RouteStops stops,
        ActiveModelDeployment deployment,
        SeatForecastModel model
    ) {
        Instant generatedAt = clock.instant();
        StopDemandStatistics statistics = stopDemandStatisticsOf(batch, deployment, generatedAt);
        seatForecastRepository.save(forecastsOf(
            batch, stops, statistics, deployment, model, generatedAt, demandStatisticsRevisionOf(batch, deployment)));
        seatForecastRepository.markForecastCompleted(batch.observationBatchId(), generatedAt);
    }

    /**
     * 이 batch 의 예보가 읽을 셀 통계. batch 하나에 한 번만 읽는다.
     *
     * <p>셀 하나만 집어 오는 길이 없다. z화도 이웃 폴백도 구간합도 같은 세대의 행 전부가 손에 있어야
     * 닫힌다. 차량마다 다시 읽으면 같은 세대를 수십 번 읽게 된다.
     *
     * <p>시간대는 계산 시각으로 가른다. 그 차량이 대상 정류장에 도착할 시각이 더 맞지만 그 시각을
     * 우리는 모른다. 12정류장 앞이 중앙값 26.5분이라 아침과 저녁의 경계에서만 갈린다.
     */
    private StopDemandStatistics stopDemandStatisticsOf(
        PendingForecastBatch batch,
        ActiveModelDeployment deployment,
        Instant generatedAt
    ) {
        return stopDemandStatisticsRepository.read(
            batch.routeVersionId(), TimeSlot.of(generatedAt, clock), deployment.calculationVersion());
    }

    /**
     * 이 예보가 읽은 셀 통계 세대.
     *
     * <p>배포가 든 계산 규칙 판과 같은 행만 읽는다. 자리가 찬 비율도 순승차 비율도 정원으로 나눈 값이라
     * 규칙이 다르면 값의 뜻이 달라진다.
     *
     * <p>그 규칙으로 낸 세대가 아직 없으면 0 이다. 셀 통계가 비는 동안은 이웃 폴백만 도는데,
     * 어느 세대도 안 읽었다는 것을 이 값이 남긴다. 노선 개편 직후 새 판본도 같은 자리다.
     */
    private int demandStatisticsRevisionOf(
        PendingForecastBatch batch,
        ActiveModelDeployment deployment
    ) {
        return stopDemandStatisticsRepository.currentRevision(
            batch.routeVersionId(), deployment.calculationVersion());
    }

    private List<SeatForecast> forecastsOf(
        PendingForecastBatch batch,
        RouteStops stops,
        StopDemandStatistics statistics,
        ActiveModelDeployment deployment,
        SeatForecastModel model,
        Instant generatedAt,
        final int demandStatisticsRevision
    ) {
        List<SeatForecast> forecasts = new ArrayList<>();
        for (VehicleTrajectory trajectory : vehicleTrajectoryRepository.readTrajectories(batch.observationBatchId())) {
            for (VehicleStopTarget target : stops.targetsAheadOf(trajectory.observation())) {
                forecasts.add(SeatForecast.of(
                    trajectory.vehicleObservationId(),
                    target,
                    model.predict(new SeatForecastInput(target, trajectory, statistics, stops)),
                    deployment,
                    demandStatisticsRevision,
                    generatedAt));
            }
        }
        return forecasts;
    }
}
