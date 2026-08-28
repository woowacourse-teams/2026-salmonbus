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

    /**
     * 어느 셀 통계 세대를 읽었는지.
     *
     * <p>셀 통계 집계가 아직 없어 읽은 세대가 없다는 뜻으로 0 을 쓴다. 집계 배치가 들어오면
     * 그때 읽은 세대로 바뀐다. 그때까지 이 값이 DB 에 닿을 일은 없다. 도는 배포가 없으면
     * 예보 행 자체가 안 생기고, 배포는 계수 번들이 와야 선다.
     */
    private static final int NO_DEMAND_STATISTICS_READ = 0;

    private final VehicleTrajectoryRepository vehicleTrajectoryRepository;
    private final SeatForecastRepository seatForecastRepository;
    private final Clock clock;

    public ForecastBatchWriter(
        VehicleTrajectoryRepository vehicleTrajectoryRepository,
        SeatForecastRepository seatForecastRepository,
        Clock clock
    ) {
        this.vehicleTrajectoryRepository = vehicleTrajectoryRepository;
        this.seatForecastRepository = seatForecastRepository;
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
        seatForecastRepository.save(forecastsOf(batch, stops, deployment, model, generatedAt));
        seatForecastRepository.markForecastCompleted(batch.observationBatchId(), generatedAt);
    }

    private List<SeatForecast> forecastsOf(
        PendingForecastBatch batch,
        RouteStops stops,
        ActiveModelDeployment deployment,
        SeatForecastModel model,
        Instant generatedAt
    ) {
        List<SeatForecast> forecasts = new ArrayList<>();
        for (VehicleTrajectory trajectory : vehicleTrajectoryRepository.readTrajectories(batch.observationBatchId())) {
            for (VehicleStopTarget target : stops.targetsAheadOf(trajectory.observation())) {
                forecasts.add(SeatForecast.of(
                    trajectory.vehicleObservationId(),
                    target,
                    model.predict(target),
                    deployment,
                    NO_DEMAND_STATISTICS_READ,
                    generatedAt));
            }
        }
        return forecasts;
    }
}
