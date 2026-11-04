package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesService;
import com.gustler.backend.forecasting.application.les.LesFeatureQuery;
import com.gustler.backend.forecasting.application.les.LesFeatureUnavailableException;
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
import com.gustler.backend.forecasting.domain.model.VehicleStopTarget;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import com.gustler.backend.forecasting.domain.model.les.LesFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.les.LesFeatureVector;
import com.gustler.backend.forecasting.domain.model.les.LesForecastModel;
import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 조회·계산은 트랜잭션 밖에서 수행하고, 발행 확정만 별도 빈에 맡긴다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastBatchWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastBatchWriter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final VehicleTrajectoryQuery vehicleTrajectoryQuery;
    private final ForecastBatchCommitter committer;
    private final SameDayFullOutcomesService sameDayFullOutcomesService;
    private final StopDemandStatisticsRepository stopDemandStatisticsRepository;
    private final Clock clock;
    private final RouteDataQualityAccess quality;
    private LesFeatureQuery lesFeatures;

    @Autowired(required = false)
    public void setLesFeatures(LesFeatureQuery lesFeatures) {
        this.lesFeatures = lesFeatures;
    }

    public ForecastBatchWriter(
        VehicleTrajectoryQuery vehicleTrajectoryQuery,
        ForecastBatchCommitter committer,
        SameDayFullOutcomesService sameDayFullOutcomesService,
        StopDemandStatisticsRepository stopDemandStatisticsRepository,
        Clock clock,
        RouteDataQualityAccess quality) {
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
        RuntimeSnapshot runtime) {
        final long qualityRevision = quality.current(batch.routeVersionId());
        Instant generatedAt = clock.instant();
        if (runtime.model() instanceof LesForecastModel les) {
            return writeLes(batch, stops, runtime, les, generatedAt, qualityRevision);
        }
        TimeSlot timeSlot = ForecastTimeSlot.of(batch, clock);
        StopDemandStatistics statistics = stopDemandStatisticsOf(batch, runtime, timeSlot);
        Map<Integer, SameDayFullOutcomes> sameDayOutcomes = sameDayFullOutcomesService.outcomesFor(batch.routeId(),
            runtime.deploymentId(), batch.responseReceivedAt());
        List<EvaluationSamplingSource> sources = new ArrayList<>();
        List<SeatForecast> predictions = forecastsOf(batch, stops, statistics, sameDayOutcomes, runtime, generatedAt,
            sources);
        ForecastPublication publication = new ForecastPublication(
            batch.observationBatchId(), batch.routeVersionId(), runtime.deploymentId(), statistics.revision(),
            qualityRevision, batch.responseReceivedAt(), generatedAt, generatedAt, predictions);
        return committer.commit(batch, publication, sources);
    }

    /** 레스는 고정 통계와 당일 원관측 입력을 사용하며 기존 정산 기반 확률 보정을 읽지 않는다. */
    private int writeLes(
        PendingForecastBatch batch,
        RouteStops stops,
        RuntimeSnapshot runtime,
        LesForecastModel model,
        Instant generatedAt,
        final long qualityRevision) {
        if (lesFeatures == null) {
            throw new IllegalStateException("레스 입력 조회기가 등록되지 않았다");
        }
        LesRouteStatistics statistics = model.statistics(stops);
        List<SeatForecast> predictions = new ArrayList<>();
        ZonedDateTime localTime = batch.responseReceivedAt().atZone(ZoneId.of("Asia/Seoul"));
        final int minute = localTime.getHour() * 60 + localTime.getMinute();
        if (!isValidatedRush(statistics, localTime, minute)) {
            LOGGER.info("event=forecast_batch_withheld reason=LES_OUTSIDE_VALIDATED_RUSH_SCOPE batchId={}",
                batch.observationBatchId());
            return committer.commit(batch, new ForecastPublication(batch.observationBatchId(),
                batch.routeVersionId(), runtime.deploymentId(), 0, qualityRevision,
                batch.responseReceivedAt(), generatedAt, generatedAt, List.of()), List.of());
        }
        Map<Long, LesFeatureSnapshot> snapshots = readLesFeatures(batch, stops, statistics);
        for (LesFeatureSnapshot snapshot : snapshots.values()) {
            List<SeatForecast> vehiclePredictions = new ArrayList<>();
            for (VehicleStopTarget target : stops.targetsAheadOf(snapshot.observation())) {
                LesFeatureVector vector = snapshot.forTarget(target.stopOrder());
                vehiclePredictions.add(lesForecast(batch, runtime, model, generatedAt,
                    statistics, snapshot, target, vector));
            }
            predictions.addAll(vehiclePredictions);
        }
        return committer.commit(batch, new ForecastPublication(batch.observationBatchId(),
            batch.routeVersionId(), runtime.deploymentId(), 0, qualityRevision,
            batch.responseReceivedAt(), generatedAt, generatedAt, predictions), List.of());
    }

    private boolean isValidatedRush(LesRouteStatistics statistics, ZonedDateTime localTime, final int minute) {
        if (!"work".equals(statistics.dayType(localTime.toLocalDate()))) {
            return false;
        }
        return isMorningRush(minute) || isEveningRush(minute);
    }

    private boolean isMorningRush(final int minute) {
        return minute >= 360 && minute < 600;
    }

    private boolean isEveningRush(final int minute) {
        return minute >= 1020 && minute < 1260;
    }

    /** 자료 부족은 발행 확정 없이 전파해 복구 뒤 같은 배치를 재처리한다. */
    private Map<Long, LesFeatureSnapshot> readLesFeatures(
        PendingForecastBatch batch,
        RouteStops stops,
        LesRouteStatistics statistics) {
        try {
            return lesFeatures.read(batch.observationBatchId(), stops, statistics);
        } catch (LesFeatureUnavailableException error) {
            LOGGER.warn("event=forecast_batch_withheld reason={} batchId={}", error.getMessage(),
                batch.observationBatchId());
            throw error;
        }
    }

    private SeatForecast lesForecast(
        PendingForecastBatch batch,
        RuntimeSnapshot runtime,
        LesForecastModel model,
        Instant generatedAt,
        LesRouteStatistics statistics,
        LesFeatureSnapshot snapshot,
        VehicleStopTarget target,
        LesFeatureVector vector) {
        ObjectNode evidence = (ObjectNode) JSON.readTree(model.evidence(statistics.route(), target.stopOrder()));
        evidence.put("asOf", batch.responseReceivedAt().toString());
        evidence.put("vehicleObservationId", snapshot.vehicleObservationId());
        evidence.put("sourceBatchId", batch.observationBatchId());
        evidence.set("seat88", nullableVector(vector.seat88()));
        evidence.set("probability80", nullableVector(vector.probability80()));
        return SeatForecast.of(snapshot.vehicleObservationId(), target,
            model.predict(vector), runtime.deploymentId(), 0, generatedAt)
            .withLesEvidence(JSON.writeValueAsString(evidence), model.bundleDigest());
    }

    private static ArrayNode nullableVector(double[] vector) {
        ArrayNode result = JSON.createArrayNode();
        for (final double value : vector) {
            if (Double.isNaN(value)) {
                result.addNull();
                continue;
            }
            result.add(value);
        }
        return result;
    }

    /** 관측 시점에 사용할 수 있었던 통계 값과 버전을 함께 읽는다. */
    private StopDemandStatistics stopDemandStatisticsOf(
        PendingForecastBatch batch,
        RuntimeSnapshot runtime,
        TimeSlot timeSlot) {
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
        List<EvaluationSamplingSource> sources) {
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
        Instant generatedAt) {
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
        Instant generatedAt) {
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
