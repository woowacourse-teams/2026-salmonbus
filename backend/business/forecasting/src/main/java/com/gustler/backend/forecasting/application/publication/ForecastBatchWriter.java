package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureUnavailableException;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareInputConfirmation;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePredictionAssembler;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 조회·계산은 트랜잭션 밖에서 수행하고, 발행 확정만 별도 빈에 맡긴다.
 *
 * <p>확정 판본 배치를 끝내면(발행 커밋, 빈 발행 커밋, 입력 부족으로 발행 없이 실패) 등록된 {@link LegacyForecastListener}에
 * 결과를 넘긴다. 넘기기는 결과와 반환값·예외에 영향을 주지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastBatchWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastBatchWriter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 입력 조회가 출퇴근 범위 밖이라고 거절한 결과다. 빈 결과와 구분하려고 동일성으로 비교한다. */
    private static final Map<Long, AlightingAwareFeatureSnapshot> OUT_OF_SCOPE =
        Collections.unmodifiableMap(new HashMap<>());

    private final ForecastBatchCommitter committer;
    private final Clock clock;
    private final RouteWorkAccess routeWork;
    private AlightingAwareFeatureQuery alightingFeatures;
    private AlightingAwareInputConfirmation confirmedInput;
    private boolean compactSettlementWrite;
    private LegacyForecastListener legacyListener;

    @Value("${forecast.compact-settlement-write:false}")
    public void setCompactSettlementWrite(boolean enabled) {
        this.compactSettlementWrite = enabled;
    }

    @Autowired(required = false)
    public void setAlightingAwareFeatures(AlightingAwareFeatureQuery alightingFeatures) {
        this.alightingFeatures = alightingFeatures;
    }

    @Autowired(required = false)
    public void setConfirmedInput(AlightingAwareInputConfirmation confirmedInput) {
        this.confirmedInput = confirmedInput;
    }

    /** 그림자 실행이 켜졌을 때만 등록된다. */
    @Autowired(required = false)
    public void setLegacyForecastListener(LegacyForecastListener legacyListener) {
        this.legacyListener = legacyListener;
    }

    public ForecastBatchWriter(
        ForecastBatchCommitter committer,
        Clock clock,
        RouteWorkAccess routeWork
    ) {
        this.committer = committer;
        this.clock = clock;
        this.routeWork = routeWork;
    }

    /** 이번 호출에서 저장한 예보 수. 빈 발행도 기존처럼 처리 완료로 확정한다. */
    @Transactional(propagation = Propagation.NEVER)
    public int writeForecastsOf(
        PendingForecastBatch batch,
        RouteStops stops,
        RuntimeSnapshot runtime) {
        final long qualityRevision = routeWork.current(batch.routeVersionId());
        Instant generatedAt = clock.instant();
        return writeAlightingAware(batch, stops, runtime, runtime.model(), generatedAt, qualityRevision);
    }

    /** 하차 반영 모델은 고정 통계와 당일 원관측 입력을 사용하며 기존 정산 기반 확률 보정을 읽지 않는다. */
    private int writeAlightingAware(
        PendingForecastBatch batch,
        RouteStops stops,
        RuntimeSnapshot runtime,
        AlightingAwareSeatForecastModel model,
        Instant generatedAt,
        final long qualityRevision
    ) {
        if (alightingFeatures == null) {
            throw new IllegalStateException("하차 반영 입력 조회기가 등록되지 않았다");
        }
        AlightingAwareRouteStatistics statistics = model.statistics(stops);
        List<SeatForecast> predictions = new ArrayList<>();
        if (statistics.confirmedProfile()) {
            if (confirmedInput == null) {
                throw new IllegalStateException("확인 시각 입력 확정기가 등록되지 않았다");
            }
            if (!confirmedInput.confirm(batch.observationBatchId(), generatedAt)) {
                final int saved = commitAlightingAware(batch, new ForecastPublication(batch.observationBatchId(),
                    batch.routeVersionId(), runtime.deploymentId(), 0, qualityRevision, batch.responseReceivedAt(),
                    generatedAt, generatedAt, List.of()), statistics);
                notifyLegacy(batch, stops, runtime, statistics, generatedAt, LegacyForecastResult.Kind.NOT_CONFIRMED,
                    null, List.of());
                return saved;
            }
        }
        ZonedDateTime localTime = batch.responseReceivedAt().atZone(ZoneId.of("Asia/Seoul"));
        if (!statistics.confirmedProfile() && !statistics.timeCell(localTime).isRush()) {
            LOGGER.info("event=forecast_batch_withheld reason=ALIGHTING_AWARE_OUTSIDE_VALIDATED_RUSH_SCOPE batchId={}",
                batch.observationBatchId());
            return commitAlightingAware(batch, new ForecastPublication(batch.observationBatchId(),
                batch.routeVersionId(), runtime.deploymentId(), 0, qualityRevision,
                batch.responseReceivedAt(), generatedAt, generatedAt, List.of()), statistics);
        }
        Map<Long, AlightingAwareFeatureSnapshot> snapshots;
        try {
            snapshots = readAlightingAwareFeatures(batch, stops, statistics, model.supportsExtendedHours());
        } catch (RuntimeException unavailable) {
            notifyLegacy(batch, stops, runtime, statistics, generatedAt, LegacyForecastResult.Kind.FAILED,
                unavailable.getMessage(), List.of());
            throw unavailable;
        }
        AlightingAwarePredictionAssembler assembler = new AlightingAwarePredictionAssembler(model, statistics, stops);
        for (AlightingAwareFeatureSnapshot snapshot : snapshots.values()) {
            Optional<AlightingAwarePredictionAssembler.VehiclePrediction> vehicle = assembler.predict(snapshot);
            if (vehicle.isEmpty()) {
                continue;
            }
            for (AlightingAwarePredictionAssembler.TargetPrediction target : vehicle.get().targets()) {
                predictions.add(alightingForecast(batch, runtime, model, generatedAt, statistics, snapshot, target,
                    vehicle.get().extended()));
            }
        }
        ForecastPublication publication = new ForecastPublication(batch.observationBatchId(),
            batch.routeVersionId(), runtime.deploymentId(), 0, qualityRevision,
            batch.responseReceivedAt(), generatedAt, generatedAt, predictions,
            compactSettlementContext(model, statistics, predictions));
        final int saved = commitAlightingAware(batch, publication, statistics);
        notifyLegacy(batch, stops, runtime, statistics, generatedAt, publishedKind(snapshots), null,
            publication.predictions());
        return saved;
    }

    /** 압축 모드에서 예보가 있을 때만 정산 근거를 만들고, 그 밖에는 {@code null}이다. */
    private String compactSettlementContext(
        AlightingAwareSeatForecastModel model,
        AlightingAwareRouteStatistics statistics,
        List<SeatForecast> predictions
    ) {
        if (!compactSettlementWrite || predictions.isEmpty()) {
            return null;
        }
        return settlementContext(model, statistics, predictions);
    }

    private LegacyForecastResult.Kind publishedKind(Map<Long, AlightingAwareFeatureSnapshot> snapshots) {
        if (snapshots == OUT_OF_SCOPE) {
            return LegacyForecastResult.Kind.OUT_OF_SCOPE;
        }
        return LegacyForecastResult.Kind.PUBLISHED;
    }

    /** 확정 판본 배치의 결과만 넘긴다. 받는 쪽의 실패는 로그만 남기고 옛 경로의 결과를 바꾸지 않는다. */
    private void notifyLegacy(
        PendingForecastBatch batch,
        RouteStops stops,
        RuntimeSnapshot runtime,
        AlightingAwareRouteStatistics statistics,
        Instant generatedAt,
        LegacyForecastResult.Kind kind,
        String reason,
        List<SeatForecast> predictions
    ) {
        if (legacyListener == null || !statistics.confirmedProfile()) {
            return;
        }
        try {
            legacyListener.batchFinished(new LegacyForecastResult(batch, stops, runtime, statistics, generatedAt, kind,
                reason, offeredPredictions(kind, predictions)));
        } catch (RuntimeException failure) {
            LOGGER.warn("event=forecast_shadow_offer_failed batchId={} exceptionType={}", batch.observationBatchId(),
                failure.getClass().getSimpleName());
        }
    }

    private List<SeatForecast> offeredPredictions(LegacyForecastResult.Kind kind, List<SeatForecast> predictions) {
        if (kind == LegacyForecastResult.Kind.OUT_OF_SCOPE) {
            return List.of();
        }
        return predictions;
    }

    private int commitAlightingAware(PendingForecastBatch batch, ForecastPublication publication, AlightingAwareRouteStatistics statistics) {
        if (statistics.confirmedProfile()) {
            return committer.commitConfirmed(batch, publication);
        }
        return committer.commit(batch, publication);
    }

    /** 자료 부족은 발행 확정 없이 전파해 복구 뒤 같은 배치를 재처리한다. */
    private Map<Long, AlightingAwareFeatureSnapshot> readAlightingAwareFeatures(
        PendingForecastBatch batch,
        RouteStops stops,
        AlightingAwareRouteStatistics statistics,
        final boolean extendedHours
    ) {
        try {
            if (extendedHours) {
                return alightingFeatures.readExtended(batch.observationBatchId(), stops, statistics);
            }
            return alightingFeatures.read(batch.observationBatchId(), stops, statistics);
        } catch (AlightingAwareFeatureUnavailableException error) {
            LOGGER.warn("event=forecast_batch_withheld reason={} batchId={}", error.getMessage(),
                batch.observationBatchId());
            if ("ALIGHTING_AWARE_OUTSIDE_VALIDATED_RUSH_SCOPE".equals(error.getMessage())) {
                return OUT_OF_SCOPE;
            }
            throw error;
        }
    }

    private String settlementContext(
        AlightingAwareSeatForecastModel model, AlightingAwareRouteStatistics statistics, List<SeatForecast> predictions
    ) {
        Map<Integer, String> targets = model.settlementEvidence(statistics.route());
        if (predictions.stream().anyMatch(row -> !targets.containsKey(row.targetStopOrder()))) {
            throw new IllegalStateException("예보 대상 정류장의 정산 근거가 없다");
        }
        return SettlementContextDocument.of(model, statistics);
    }

    private SeatForecast alightingForecast(
        PendingForecastBatch batch,
        RuntimeSnapshot runtime,
        AlightingAwareSeatForecastModel model,
        Instant generatedAt,
        AlightingAwareRouteStatistics statistics,
        AlightingAwareFeatureSnapshot snapshot,
        AlightingAwarePredictionAssembler.TargetPrediction target,
        final boolean extended
    ) {
        ObjectNode evidence = baseEvidence(model, statistics, target);
        if (!compactSettlementWrite) {
            evidence.put("inputProfile", statistics.profile().input());
            evidence.put("capacityPolicy", "vehicle-capacity-snapshot-v1");
            evidence.put("asOf", batch.responseReceivedAt().toString());
            evidence.put("vehicleObservationId", snapshot.vehicleObservationId());
            evidence.put("sourceBatchId", batch.observationBatchId());
            if (statistics.confirmedProfile()) {
                evidence.put("asOf", snapshot.source().receivedAt().toString());
                evidence.put("tripStartObservationId", snapshot.provenance().tripStartObservationId());
            }
            if (extended) {
                evidence.put("forecastInput", "alighting-extended-six-v1");
            }
        }
        evidence.put("capacity", snapshot.capacity());
        return SeatForecast.of(snapshot.vehicleObservationId(), target.target(),
            target.result(), runtime.deploymentId(), 0, generatedAt)
            .withAlightingAwareEvidence(JSON.writeValueAsString(evidence), model.bundleDigest(), statistics.confirmedProfile()
                ? "alighting-confirmed-capacity-snapshot-v3" : "alighting-capacity-snapshot-v2");
    }

    private ObjectNode baseEvidence(
        AlightingAwareSeatForecastModel model,
        AlightingAwareRouteStatistics statistics,
        AlightingAwarePredictionAssembler.TargetPrediction target
    ) {
        if (compactSettlementWrite) {
            return JSON.createObjectNode();
        }
        return (ObjectNode) JSON.readTree(model.evidence(statistics.route(), target.target().stopOrder()));
    }
}
