package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesService;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationSamplingPoint;
import com.gustler.backend.forecasting.domain.model.ForecastFeatureContract;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SameDayFullOutcomes;
import com.gustler.backend.forecasting.domain.model.SeatForecastInput;
import com.gustler.backend.forecasting.domain.model.SeatRangeException;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastTimeSlot;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 조회·계산은 트랜잭션 밖에서 수행하고, 발행 확정만 별도 빈에 맡긴다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastBatchWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastBatchWriter.class);

    private final VehicleTrajectoryQuery vehicleTrajectoryQuery;
    private final ForecastBatchCommitter committer;
    private final SameDayFullOutcomesService sameDayFullOutcomesService;
    private final StopDemandStatisticsRepository stopDemandStatisticsRepository;
    private final Clock clock;
    private final RouteDataQualityAccess quality;

    public ForecastBatchWriter(
        VehicleTrajectoryQuery vehicleTrajectoryQuery,
        ForecastBatchCommitter committer,
        SameDayFullOutcomesService sameDayFullOutcomesService,
        StopDemandStatisticsRepository stopDemandStatisticsRepository,
        Clock clock,
        RouteDataQualityAccess quality
    ) {
        this.vehicleTrajectoryQuery = vehicleTrajectoryQuery;
        this.committer = committer;
        this.sameDayFullOutcomesService = sameDayFullOutcomesService;
        this.stopDemandStatisticsRepository = stopDemandStatisticsRepository;
        this.clock = clock;
        this.quality = quality;
    }

    /** 이번 호출에서 저장한 예보 수. 빈 발행도 기존처럼 처리 완료로 확정한다. */
    @Transactional(propagation = Propagation.NEVER)
    public int writeForecastsOf(
        PendingForecastBatch batch,
        RouteStops stops,
        RuntimeSnapshot runtime
    ) {
        final long qualityRevision = quality.current(batch.routeVersionId());
        Instant generatedAt = clock.instant();
        TimeSlot timeSlot = ForecastTimeSlot.of(batch, clock);
        StopDemandStatistics statistics = stopDemandStatisticsOf(batch, runtime, timeSlot);
        Map<Integer, SameDayFullOutcomes> sameDayOutcomes =
            sameDayFullOutcomesService.outcomesFor(batch.routeId(), runtime.deploymentId(), batch.responseReceivedAt());
        List<EvaluationSamplingSource> sources = new ArrayList<>();
        List<SeatForecast> predictions =
            forecastsOf(batch, stops, statistics, sameDayOutcomes, runtime, generatedAt, sources);
        ForecastPublication publication = new ForecastPublication(
            batch.observationBatchId(), batch.routeVersionId(), runtime.deploymentId(), statistics.revision(),
            qualityRevision, batch.responseReceivedAt(), generatedAt, generatedAt, predictions);
        return committer.commit(batch, publication, sources);
    }

    /** 관측 시점에 사용할 수 있었던 통계 값과 버전을 함께 읽는다. */
    private StopDemandStatistics stopDemandStatisticsOf(
        PendingForecastBatch batch,
        RuntimeSnapshot runtime,
        TimeSlot timeSlot
    ) {
        String statisticsVersion = calculationVersionOf(runtime.featureContractVersion());
        return stopDemandStatisticsRepository.readAsOf(
            batch.routeVersionId(), timeSlot, statisticsVersion, batch.responseReceivedAt());
    }

    private List<SeatForecast> forecastsOf(
        PendingForecastBatch batch,
        RouteStops stops,
        StopDemandStatistics statistics,
        Map<Integer, SameDayFullOutcomes> sameDayOutcomes,
        RuntimeSnapshot runtime,
        Instant generatedAt,
        List<EvaluationSamplingSource> sources
    ) {
        return vehicleTrajectoryQuery.readTrajectories(batch.observationBatchId())
            .stream()
            .flatMap(trajectory -> {
                List<SeatForecast> forecasts = forecastsOfVehicleOrEmpty(
                    batch, trajectory, stops, statistics, sameDayOutcomes, runtime, generatedAt);
                ObservedVehicle observation = trajectory.observation();
                if (!forecasts.isEmpty() && hasVehicleId(observation)) {
                    String direction = stops.stops()
                        .stream()
                        .filter(stop -> stop.stopOrder() == observation.passedStopOrder())
                        .map(stop -> stop.direction().name())
                        .findFirst()
                        .orElse(RouteDirection.UNKNOWN.name());
                    sources.add(new EvaluationSamplingSource(trajectory.vehicleObservationId(), stops.sourceRouteId(),
                        new EvaluationSamplingPoint(batch.routeVersionId(), runtime.deploymentId(),
                            observation.vehicleId(), observation.passedStopOrder(), direction,
                            observation.observedAt())));
                }
                return forecasts.stream();
            })
            .toList();
    }

    private boolean hasVehicleId(ObservedVehicle observation) {
        String vehicleId = observation.vehicleId();
        return vehicleId != null && !vehicleId.isBlank();
    }

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
            LOGGER.warn("event=forecast_vehicle_skipped reason=MODEL_INPUT_OUT_OF_RANGE "
                    + "batchId={} vehicleObservationId={} modelDeploymentId={} "
                    + "currentSeats={} capacity={} field={} value={} minimum={} maximum={}",
                batch.observationBatchId(), trajectory.vehicleObservationId(), runtime.deploymentId(),
                trajectory.observation().remainingSeats(), trajectory.maximumSeatsEverObserved(),
                e.inputField(), e.inputValue(), e.minimum(), e.maximum());
            return List.of();
        }
    }

    /** 목록을 완성한 뒤 반환해 도중에 실패한 차량의 일부 예보가 배치에 섞이지 않게 한다. */
    private List<SeatForecast> forecastsOfVehicle(
        VehicleTrajectory trajectory,
        RouteStops stops,
        StopDemandStatistics statistics,
        Map<Integer, SameDayFullOutcomes> sameDayOutcomes,
        RuntimeSnapshot runtime,
        Instant generatedAt
    ) {
        return stops.targetsAheadOf(trajectory.observation())
            .stream()
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

    private static String calculationVersionOf(String featureContractVersion) {
        if (ForecastFeatureContract.STATISTICS_VERSION.equals(featureContractVersion)) {
            return ForecastFeatureContract.STATISTICS_CALCULATION_VERSION;
        }
        return featureContractVersion;
    }
}
