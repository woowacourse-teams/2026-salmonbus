package com.gustler.backend.observations.application;

import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import com.gustler.backend.observations.api.VehicleObservationsStored.Row;
import com.gustler.backend.observations.domain.CollectedObservations;
import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.CollectionBatchRepository;
import com.gustler.backend.observations.domain.ObservationBatchConclusion;
import com.gustler.backend.observations.domain.StoredObservations;
import com.gustler.backend.observations.domain.UpstreamObservationRow;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 관측 저장과 품질 조사 등록을 같은 트랜잭션에서 조율한다. */
@Component
public class ObservationLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObservationLoader.class);
    private final CollectionBatchRepository batches;
    private final List<CollectionQualityHook> qualityHooks;
    private final List<ObservationDeliveryHook> deliveryHooks;

    public ObservationLoader(CollectionBatchRepository batches, List<CollectionQualityHook> qualityHooks,
        List<ObservationDeliveryHook> deliveryHooks) {
        this.batches = batches;
        this.qualityHooks = List.copyOf(qualityHooks);
        this.deliveryHooks = List.copyOf(deliveryHooks);
    }

    /**
     * 정규화된 수신 관측과 수집 상태를 같은 트랜잭션에서 저장한다. 품질 hook은 그 안에서 실행하고 관측 전달은 커밋 경계를 따른다.
     */
    @Transactional(readOnly = false)
    public void load(
        final long batchId,
        ObservationBatchConclusion conclusion,
        CollectedObservations collected,
        OffsetDateTime responseReceivedAt
    ) {
        logExcludedRows(batchId, collected);
        CollectionBatch batch = batches.getById(batchId);
        batch.conclude(conclusion, responseReceivedAt);
        batch.countRows(collected);
        StoredObservations stored = new StoredObservations(batchId, batch.routeVersionId(),
            responseReceivedAt.toInstant(), batches.saveObservations(batch, collected.storableRows()));
        VehicleObservationsStored event =
            new VehicleObservationsStored(stored.batchId(), stored.routeVersionId(), stored.observedAt(),
                stored.rows()
                    .stream()
                    .map(row -> new Row(
                        row.observationId(), row.vehicleId(), row.remainingSeats()))
                    .toList());
        // 동기 이벤트는 관측 저장 transaction 안에서 처리된다. 정상 관측은 추가 SQL이 없다.
        qualityHooks.forEach(hook -> hook.observationsStored(event));
        batches.save(batch);
        ObservationDeliveryBatch delivery = new ObservationDeliveryBatch(batchId, batch.routeVersionId(),
            batch.state().attemptNumber(), Kind.STORED, stored.observedAt(),
            stored.rows()
                .stream()
                .map(row -> new ObservationDeliveryBatch.Row(
                    row.observationId(), row.vehicleId(), row.remainingSeats(),
                    row.passedStopOrder(), row.crowdLevel(), row.stopOrder(), row.runningState()))
                .toList());
        deliveryHooks.forEach(hook -> hook.record(delivery));
    }

    /**
     * 제외한 행은 필드별 값을 기록한다. 응답 원문을 통째로 기록하면 번호판이 로그에 노출된다.
     * 차량 아이디와 번호판은 기록하지 않는다.
     */
    private void logExcludedRows(final long batchId, CollectedObservations collected) {
        for (UpstreamObservationRow row : collected.excludedRows()) {
            LOGGER.warn("쌓을 수 없는 관측을 뺐다. 묶음={} 상류행={} 운행상태={} 정류소순번={}",
                batchId, row.sourceRowNumber(), row.observation().runningState(), row.observation().stopSequence());
        }
    }
}
