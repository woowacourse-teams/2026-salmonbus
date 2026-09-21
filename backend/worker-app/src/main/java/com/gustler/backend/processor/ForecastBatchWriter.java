package com.gustler.backend.processor;

import com.gustler.backend.processor.seatdistribution.RuntimeSnapshot;
import com.gustler.backend.processor.seatdistribution.SameDayFullOutcomes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ForecastBatchWriter.class);

    private final VehicleTrajectoryRepository vehicleTrajectoryRepository;
    private final SeatForecastRepository seatForecastRepository;
    private final StopDemandStatisticsRepository stopDemandStatisticsRepository;
    private final Clock clock;
    private final TripQualityRepository tripQuality;

    public ForecastBatchWriter(
        VehicleTrajectoryRepository vehicleTrajectoryRepository,
        SeatForecastRepository seatForecastRepository,
        StopDemandStatisticsRepository stopDemandStatisticsRepository,
        Clock clock,
        TripQualityRepository tripQuality
    ) {
        this.vehicleTrajectoryRepository = vehicleTrajectoryRepository;
        this.seatForecastRepository = seatForecastRepository;
        this.stopDemandStatisticsRepository = stopDemandStatisticsRepository;
        this.clock = clock;
        this.tripQuality = tripQuality;
    }

    /** 모델 적재 전에 실행한다. 판정 저장은 각 묶음의 독립 transaction이다. */
    public void assessRecentBatches() {
        tripQuality.findRecentUnassessedBatches(clock.instant().minus(Duration.ofMinutes(5)))
            .forEach(tripQuality::assessBatch);
    }

    @Transactional
    public void writeForecastsOf(
        PendingForecastBatch batch,
        RouteStops stops,
        RuntimeSnapshot runtime
    ) {
        tripQuality.assessBatch(batch.observationBatchId());
        tripQuality.lockRoute(batch.routeVersionId());
        Instant generatedAt = clock.instant();
        TimeSlot timeSlot = ForecastTimeSlot.of(batch, clock);
        StopDemandStatistics statistics = stopDemandStatisticsOf(batch, runtime, timeSlot);
        Map<Integer, SameDayFullOutcomes> sameDayOutcomes =
            seatForecastRepository.readSameDayFullOutcomes(
                batch.routeVersionId(), batch.responseReceivedAt());
        seatForecastRepository.save(
            forecastsOf(batch, stops, statistics, sameDayOutcomes, runtime, generatedAt));
        seatForecastRepository.markForecastCompleted(batch.observationBatchId(), generatedAt);
    }

    /**
     * 이 batch 의 예보가 읽을 셀 통계. batch 하나에 한 번만 읽는다.
     *
     * <p>셀 하나만 집어 오는 길이 없다. z화도 이웃 폴백도 구간합도 같은 세대의 행 전부가 손에 있어야
     * 닫힌다. 차량마다 다시 읽으면 같은 세대를 수십 번 읽게 된다.
     *
     * <p><b>세대 번호를 따로 안 읽는다.</b> 값을 읽고 세대 번호를 다시 조회하면 그 사이에 집계가
     * 교체됐을 때 N세대 값에 N+1 세대 번호가 붙는다. 읽어 온 통계가 자기 세대를 들고 있다.
     *
     * <p>시간대는 받아서 쓴다. 여기서 다시 정하면 설계행렬이 쓰는 시간대와 갈릴 수 있다.
     * 정하는 자리는 {@link ForecastTimeSlot} 하나다.
     *
     * <p><b>세대도 관측 시각으로 고른다.</b> 지금 최신 세대를 쓰면 밀린 batch 를 뒤늦게 처리할 때
     * 그 관측 시각보다 뒤의 라벨이 들어간 셀을 읽어서, 같은 batch 를 다시 처리해도 값이 달라진다.
     */
    private StopDemandStatistics stopDemandStatisticsOf(
        PendingForecastBatch batch,
        RuntimeSnapshot runtime,
        TimeSlot timeSlot
    ) {
        return stopDemandStatisticsRepository.readAsOf(
            batch.routeVersionId(), timeSlot, runtime.featureContractVersion(), batch.responseReceivedAt());
    }

    private List<SeatForecast> forecastsOf(
        PendingForecastBatch batch,
        RouteStops stops,
        StopDemandStatistics statistics,
        Map<Integer, SameDayFullOutcomes> sameDayOutcomes,
        RuntimeSnapshot runtime,
        Instant generatedAt
    ) {
        return vehicleTrajectoryRepository.readTrajectories(batch.observationBatchId()).stream()
            .flatMap(trajectory -> forecastsOfVehicleOrEmpty(
                batch, trajectory, stops, statistics, sameDayOutcomes, runtime, generatedAt).stream())
            .toList();
    }

    /** 좌석 범위 오류만 차량 단위로 생략한다. 일반 계산·저장 오류는 배치를 실패시킨다. */
    private List<SeatForecast> forecastsOfVehicleOrEmpty(
        PendingForecastBatch batch,
        VehicleTrajectory trajectory,
        RouteStops stops,
        StopDemandStatistics statistics,
        Map<Integer, SameDayFullOutcomes> sameDayOutcomes,
        RuntimeSnapshot runtime,
        Instant generatedAt
    ) {
        try {
            return forecastsOfVehicle(trajectory, stops, statistics, sameDayOutcomes, runtime, generatedAt);
        } catch (SeatRangeException e) {
            log.warn("event=forecast_vehicle_skipped reason=MODEL_INPUT_OUT_OF_RANGE "
                    + "batchId={} vehicleObservationId={} modelDeploymentId={} "
                    + "currentSeats={} capacity={} field={} value={} minimum={} maximum={}",
                batch.observationBatchId(), trajectory.vehicleObservationId(), runtime.deploymentId(),
                trajectory.observation().remainingSeats(), trajectory.maximumSeatsEverObserved(),
                e.inputField(), e.inputValue(), e.minimum(), e.maximum());
            return List.of();
        }
    }

    /** 목록을 완성한 뒤 반환해, 도중에 실패한 차량의 일부 예보가 배치에 섞이지 않게 한다. */
    private List<SeatForecast> forecastsOfVehicle(
        VehicleTrajectory trajectory,
        RouteStops stops,
        StopDemandStatistics statistics,
        Map<Integer, SameDayFullOutcomes> sameDayOutcomes,
        RuntimeSnapshot runtime,
        Instant generatedAt
    ) {
        return stops.targetsAheadOf(trajectory.observation()).stream()
            .map(target -> SeatForecast.of(
                trajectory.vehicleObservationId(),
                target,
                runtime.model().predict(new SeatForecastInput(
                    target, trajectory, statistics, stops, statistics.timeSlot(),
                    sameDayOutcomes.get(target.distance().stopCount()))),
                runtime.deploymentId(),
                statistics.revision(),
                generatedAt))
            .toList();
    }
}
