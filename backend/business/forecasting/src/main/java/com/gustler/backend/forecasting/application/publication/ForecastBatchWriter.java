package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureUnavailableException;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareInputConfirmation;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.VehicleStopTarget;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 조회·계산은 트랜잭션 밖에서 수행하고, 발행 확정만 별도 빈에 맡긴다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastBatchWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastBatchWriter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ForecastBatchCommitter committer;
    private final Clock clock;
    private final RouteWorkAccess routeWork;
    private AlightingAwareFeatureQuery alightingFeatures;
    private AlightingAwareInputConfirmation confirmedInput;
    private boolean compactSettlementWrite;

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
                return commitAlightingAware(batch, new ForecastPublication(batch.observationBatchId(), batch.routeVersionId(),
                    runtime.deploymentId(), 0, qualityRevision, batch.responseReceivedAt(), generatedAt, generatedAt,
                    List.of()), statistics);
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
        Map<Long, AlightingAwareFeatureSnapshot> snapshots = readAlightingAwareFeatures(batch, stops, statistics, model.supportsExtendedHours());
        for (AlightingAwareFeatureSnapshot snapshot : snapshots.values()) {
            final boolean extended = statistics.confirmedProfile() && outsideRush(statistics, snapshot);
            if (extended && !model.supportsExtendedHours()) {
                continue;
            }
            List<SeatForecast> vehiclePredictions = new ArrayList<>();
            for (VehicleStopTarget target : stops.targetsAheadOf(snapshot.observation())) {
                if (statistics.confirmedProfile()
                    && statistics.stop(target.stopOrder()).down() != snapshot.source().down()) {
                    continue;
                }
                vehiclePredictions.add(alightingForecast(runtime, model, generatedAt,
                    statistics, snapshot, target, extended));
            }
            predictions.addAll(vehiclePredictions);
        }
        return commitAlightingAware(batch, new ForecastPublication(batch.observationBatchId(),
            batch.routeVersionId(), runtime.deploymentId(), 0, qualityRevision,
            batch.responseReceivedAt(), generatedAt, generatedAt, predictions,
            !compactSettlementWrite || predictions.isEmpty() ? null : settlementContext(model, statistics, predictions)), statistics);
    }

    private int commitAlightingAware(PendingForecastBatch batch, ForecastPublication publication, AlightingAwareRouteStatistics statistics) {
        if (statistics.confirmedProfile()) {
            return committer.commitConfirmed(batch, publication);
        }
        return committer.commit(batch, publication);
    }

    private boolean outsideRush(AlightingAwareRouteStatistics statistics, AlightingAwareFeatureSnapshot snapshot) {
        ZonedDateTime sourceTime = snapshot.source().receivedAt().atZone(ZoneId.of("Asia/Seoul"));
        return !statistics.timeCell(sourceTime).isRush();
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
                return Map.of();
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
        JsonNode source = JSON.readTree(targets.values().iterator().next());
        ObjectNode shared = JSON.createObjectNode();
        shared.put("capacityPolicy", "vehicle-capacity-snapshot-v1");
        shared.put("inputProfile", statistics.profile().input());
        for (String field : List.of("turn", "realStopOrders", "extendedSettlement")) {
            if (source.has(field)) {
                shared.set(field, source.get(field));
            }
        }
        if (source.has("holidays")) {
            List<String> holidays = new ArrayList<>();
            source.path("holidays").forEach(day -> holidays.add(day.asString()));
            holidays.sort(String::compareTo);
            shared.set("holidays", JSON.valueToTree(holidays));
        }
        ObjectNode stops = JSON.createObjectNode();
        targets.forEach((order, evidence) -> {
            JsonNode parameters = JSON.readTree(evidence).path("alighting").path("stops");
            stops.set(order.toString(), parameters.isArray() ? parameters : JSON.createArrayNode());
        });
        ObjectNode context = JSON.createObjectNode();
        context.set("shared", shared);
        context.set("stops", stops);
        return JSON.writeValueAsString(context);
    }

    private SeatForecast alightingForecast(
        RuntimeSnapshot runtime,
        AlightingAwareSeatForecastModel model,
        Instant generatedAt,
        AlightingAwareRouteStatistics statistics,
        AlightingAwareFeatureSnapshot snapshot,
        VehicleStopTarget target,
        final boolean extended
    ) {
        ObjectNode evidence = compactSettlementWrite ? JSON.createObjectNode()
            : (ObjectNode) JSON.readTree(model.evidence(statistics.route(), target.stopOrder()));
        if (!compactSettlementWrite) {
            evidence.put("inputProfile", statistics.profile().input());
            evidence.put("capacityPolicy", "vehicle-capacity-snapshot-v1");
        }
        evidence.put("capacity", snapshot.capacity());
        SeatForecastResult prediction = predict(model, statistics, snapshot, target, extended);
        return SeatForecast.of(snapshot.vehicleObservationId(), target,
            prediction, runtime.deploymentId(), 0, generatedAt)
            .withAlightingAwareEvidence(JSON.writeValueAsString(evidence), model.bundleDigest(), statistics.confirmedProfile()
                ? "alighting-confirmed-capacity-snapshot-v3" : "alighting-capacity-snapshot-v2");
    }

    private SeatForecastResult predict(
        AlightingAwareSeatForecastModel model,
        AlightingAwareRouteStatistics statistics,
        AlightingAwareFeatureSnapshot snapshot,
        VehicleStopTarget target,
        final boolean extended
    ) {
        if (extended) {
            ZonedDateTime local = snapshot.source().receivedAt().atZone(ZoneId.of("Asia/Seoul"));
            final boolean offDay = "off".equals(statistics.dayType(local.toLocalDate()));
            return model.predictExtended(statistics.route(), ExtendedAlightingPredictor.inputs(snapshot, target, offDay));
        }
        return model.predict(snapshot.forTarget(target.stopOrder()));
    }
}
