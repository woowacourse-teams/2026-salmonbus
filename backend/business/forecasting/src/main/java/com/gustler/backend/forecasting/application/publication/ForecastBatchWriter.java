package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesService;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.domain.publication.ForecastTimeSlot;
import com.gustler.backend.forecasting.domain.model.SeatForecastInput;
import com.gustler.backend.forecasting.domain.model.SeatRangeException;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.observations.api.CollectionInputs;

import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.SameDayFullOutcomes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 수집 배치 하나의 예측 계산과 발행을 한 트랜잭션으로 처리한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(ForecastBatchWriter.class);

    private final VehicleTrajectoryQuery vehicleTrajectoryQuery;
    private final ForecastPublicationRepository publications;
    private final ForecastEvaluationRepository evaluations;
    private final SameDayFullOutcomesService sameDayFullOutcomesService;
    private final StopDemandStatisticsRepository stopDemandStatisticsRepository;
    private final Clock clock;
    private final RouteDataQualityAccess quality;
    private final CollectionInputs collectionInputs;

    public ForecastBatchWriter(
        VehicleTrajectoryQuery vehicleTrajectoryQuery,
        ForecastPublicationRepository publications,
        ForecastEvaluationRepository evaluations,
        SameDayFullOutcomesService sameDayFullOutcomesService,
        StopDemandStatisticsRepository stopDemandStatisticsRepository,
        Clock clock,
        RouteDataQualityAccess quality,
        CollectionInputs collectionInputs
    ) {
        this.vehicleTrajectoryQuery = vehicleTrajectoryQuery;
        this.publications = publications;
        this.evaluations = evaluations;
        this.sameDayFullOutcomesService = sameDayFullOutcomesService;
        this.stopDemandStatisticsRepository = stopDemandStatisticsRepository;
        this.clock = clock;
        this.quality = quality;
        this.collectionInputs = collectionInputs;
    }

    @Transactional
    public void writeForecastsOf(
        PendingForecastBatch batch,
        RouteStops stops,
        RuntimeSnapshot runtime
    ) {
        final long qualityRevision = quality.lock(batch.routeVersionId());
        Instant generatedAt = clock.instant();
        TimeSlot timeSlot = ForecastTimeSlot.of(batch, clock);
        StopDemandStatistics statistics = stopDemandStatisticsOf(batch, runtime, timeSlot);
        Map<Integer, SameDayFullOutcomes> sameDayOutcomes =
            sameDayFullOutcomesService.outcomesFor(batch.routeId(), batch.responseReceivedAt());
        List<SeatForecast> predictions = forecastsOf(batch, stops, statistics, sameDayOutcomes, runtime, generatedAt);
        publications.save(new ForecastPublication(
            batch.observationBatchId(), batch.routeVersionId(), runtime.deploymentId(), statistics.revision(),
            qualityRevision, batch.responseReceivedAt(), generatedAt, generatedAt, predictions));
        evaluations.addPending(batch.routeVersionId(), predictions.stream()
            .map(prediction -> ForecastEvaluation.pending(prediction.vehicleObservationId(), prediction.targetStopOrder()))
            .toList());
        collectionInputs.confirmInput(batch.observationBatchId(), generatedAt);
    }

    /** 관측 시점에 사용할 수 있었던 통계 값과 버전을 함께 읽는다. */
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
        return vehicleTrajectoryQuery.readTrajectories(batch.observationBatchId()).stream()
            .flatMap(trajectory -> forecastsOfVehicleOrEmpty(
                batch, trajectory, stops, statistics, sameDayOutcomes, runtime, generatedAt).stream())
            .toList();
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
