package com.gustler.backend.observations.application;

import com.gustler.backend.observations.api.ObservationChange;
import com.gustler.backend.observations.api.ObservationChangeListener;
import com.gustler.backend.observations.api.ObservationSeatQualityChecker;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.CollectionPreparation;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.domain.ObservationRows;
import com.gustler.backend.observations.domain.VehicleObservationRow;
import com.gustler.backend.observations.infrastructure.jpa.CollectionBatchRepository;
import com.gustler.backend.quota.api.ApiCallQuota;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 호출 전 준비와 호출 후 관측·품질 저장을 각각 확정한다. 외부 HTTP는 이 경계 밖에서 수행한다. */
@Service
public class CollectionBatchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionBatchService.class);

    private final ApiCallQuota callQuota;
    private final CollectionBatchRepository batches;
    private final List<ObservationSeatQualityChecker> seatQualityChecks;
    private final List<ObservationChangeListener> changeListeners;

    public CollectionBatchService(
        ApiCallQuota callQuota,
        CollectionBatchRepository batches,
        List<ObservationSeatQualityChecker> seatQualityChecks,
        List<ObservationChangeListener> changeListeners
    ) {
        this.callQuota = callQuota;
        this.batches = batches;
        this.seatQualityChecks = List.copyOf(seatQualityChecks);
        this.changeListeners = List.copyOf(changeListeners);
    }

    /** 준비 시각의 한국 날짜에서 한도를 확보한다. 관측 저장이 실패해도 이 예약은 유지한다. */
    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
    public CollectionPreparation prepare(CollectionPlan plan, OffsetDateTime preparedAt) {
        Optional<String> keyAlias = callQuota.reserveLocation(preparedAt);
        CollectionPreparation.Batch batch = batches.prepare(plan, keyAlias.isPresent());

        if (batch.restarted()) {
            notifyObservationChange(batch.batchId(), plan.routeVersionId(), ObservationChange.Kind.CLEARED);
        }
        return new CollectionPreparation(batch.batchId(), keyAlias.isPresent(), keyAlias.orElse(null));
    }

    /** 날짜가 바뀐 뒤 한도가 없어 HTTP를 보내지 못한 준비를 종료한다. */
    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
    public void abandonBeforeSend(final long batchId) {
        batches.abandonBeforeSend(batchId);
    }

    /** 관측 저장의 롤백과 분리한다. 성공한 수집이나 업무 데이터가 남은 배치는 변경하지 않는다. */
    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public int closeUnfinished(final long batchId) {
        return batches.closeUnfinished(batchId);
    }

    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public List<Long> closePreviousRound() {
        List<Long> closedBatchIds = new ArrayList<>();

        for (Long batchId : batches.lockUnfinished(100)) {
            if (batches.closeUnfinished(batchId) == 1) {
                closedBatchIds.add(batchId);
            }
        }
        return List.copyOf(closedBatchIds);
    }

    /** 관측 저장과 수집 완료, 필요한 품질 조사 등록을 한 트랜잭션에서 수행한다. */
    @Transactional(readOnly = false)
    public CollectionResult conclude(
        final long batchId,
        ObservationResponse upstreamResponse,
        OffsetDateTime receivedAt
    ) {
        CollectionResult collectionResult = upstreamResponse.interpret(receivedAt);

        if (collectionResult.observations().isPresent()) {
            storeObservations(batchId, collectionResult, receivedAt);
            return collectionResult;
        }

        batches.conclude(batchId, collectionResult, receivedAt);

        return collectionResult;
    }

    private void storeObservations(final long batchId, CollectionResult collectionResult, OffsetDateTime receivedAt) {
        ObservationRows observationRows = collectionResult.observations().orElseThrow();
        logExcludedRows(batchId, observationRows);

        final long routeVersionId = batches.conclude(batchId, collectionResult, receivedAt);

        checkSeatQuality(batchId, routeVersionId, observationRows.hasPositiveSeats());
        notifyObservationChange(batchId, routeVersionId, ObservationChange.Kind.STORED);
    }

    private void checkSeatQuality(final long batchId, final long routeVersionId, final boolean hasPositiveSeats) {
        for (ObservationSeatQualityChecker qualityCheck : seatQualityChecks) {
            qualityCheck.observationsStored(batchId, routeVersionId, hasPositiveSeats);
        }
    }

    private void logExcludedRows(final long batchId, ObservationRows observationRows) {
        for (VehicleObservationRow row : observationRows.excludedRows()) {
            LOGGER.warn(
                    "쌓을 수 없는 관측을 뺐다. 묶음={} 상류행={} 운행상태={} 정류소순번={}",
                    batchId,
                    row.sourceRowNumber(),
                    row.observation().runningState(),
                    row.observation().stopSequence()
            );
        }
    }

    private void notifyObservationChange(final long batchId, final long routeVersionId, ObservationChange.Kind kind) {
        ObservationChange observationChange = new ObservationChange(batchId, routeVersionId, kind);

        for (ObservationChangeListener listener : changeListeners) {
            listener.record(observationChange);
        }
    }
}
