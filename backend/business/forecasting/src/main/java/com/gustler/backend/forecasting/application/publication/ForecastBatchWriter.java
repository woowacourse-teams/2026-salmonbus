package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesService;
import com.gustler.backend.forecasting.domain.model.ForecastTimeSlot;
import com.gustler.backend.forecasting.domain.model.SeatForecastInput;
import com.gustler.backend.forecasting.domain.model.SeatRangeException;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.RouteStops;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
import com.gustler.backend.forecasting.domain.publication.PublishedForecast;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectory;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryRepository;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.observations.api.CollectionInput;
import com.gustler.backend.observations.api.CollectionInputs;

import com.gustler.backend.forecasting.domain.model.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.SameDayFullOutcomes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 수집 시도 하나의 입력 확인, 예측 계산과 발행을 한 트랜잭션으로 처리한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(ForecastBatchWriter.class);

    private final VehicleTrajectoryRepository vehicleTrajectoryRepository;
    private final ForecastPublicationRepository publications;
    private final SameDayFullOutcomesService sameDayFullOutcomesService;
    private final StopDemandStatisticsRepository stopDemandStatisticsRepository;
    private final Clock clock;
    private final RouteDataQualityAccess quality;
    private final CollectionInputs collectionInputs;

    public ForecastBatchWriter(
        VehicleTrajectoryRepository vehicleTrajectoryRepository,
        ForecastPublicationRepository publications,
        SameDayFullOutcomesService sameDayFullOutcomesService,
        StopDemandStatisticsRepository stopDemandStatisticsRepository,
        Clock clock,
        RouteDataQualityAccess quality,
        CollectionInputs collectionInputs
    ) {
        this.vehicleTrajectoryRepository = vehicleTrajectoryRepository;
        this.publications = publications;
        this.sameDayFullOutcomesService = sameDayFullOutcomesService;
        this.stopDemandStatisticsRepository = stopDemandStatisticsRepository;
        this.clock = clock;
        this.quality = quality;
        this.collectionInputs = collectionInputs;
    }

    @Transactional
    public Optional<PublishedForecast> writeForecastsOf(
        PendingForecastBatch batch,
        RouteStops stops,
        RuntimeSnapshot runtime
    ) {
        final long qualityRevision = quality.lock(batch.routeVersionId());
        CollectionInput input = collectionInputs.lockForForecast(batch.observationBatchId());
        Optional<PublishedForecast> existing = publications.findBySourceBatchId(batch.observationBatchId());
        if (existing.isPresent()) {
            return existing;
        }
        if (!isCurrentSuccessfulAttempt(batch, input)) {
            log.info("event=forecast_batch_skipped reason=COLLECTION_ATTEMPT_CHANGED batchId={} attemptNumber={}",
                batch.observationBatchId(), batch.attemptNumber());
            return Optional.empty();
        }
        Instant generatedAt = clock.instant();
        TimeSlot timeSlot = ForecastTimeSlot.of(batch, clock);
        StopDemandStatistics statistics = stopDemandStatisticsOf(batch, runtime, timeSlot);
        Map<Integer, SameDayFullOutcomes> sameDayOutcomes =
            sameDayFullOutcomesService.outcomesFor(batch.routeId(), batch.responseReceivedAt());
        List<SeatForecast> predictions = forecastsOf(batch, stops, statistics, sameDayOutcomes, runtime, generatedAt);
        ForecastPublication publication = new ForecastPublication(
            batch.observationBatchId(), batch.attemptNumber(), batch.routeVersionId(), runtime.deploymentId(),
            statistics.revision(), qualityRevision, batch.responseReceivedAt(), generatedAt, clock.instant(), predictions);
        collectionInputs.confirmInput(batch.observationBatchId(), batch.attemptNumber(), publication.publishedAt());
        return Optional.of(publications.save(publication));
    }

    private static boolean isCurrentSuccessfulAttempt(PendingForecastBatch batch, CollectionInput input) {
        return input.successful()
            && input.batchId() == batch.observationBatchId()
            && input.routeVersionId() == batch.routeVersionId()
            && input.attemptNumber() == batch.attemptNumber()
            && batch.responseReceivedAt().equals(input.observedAt());
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
        return vehicleTrajectoryRepository.readTrajectories(batch.observationBatchId()).stream()
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
