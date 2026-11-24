package com.gustler.backend.observations.application;

import com.gustler.backend.observations.api.ObservationChange;
import com.gustler.backend.observations.api.ObservationChangeListener;
import com.gustler.backend.observations.api.ObservationSeatQualityChecker;
import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.CollectionInputConfirmedException;
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
        CollectionBatch batch = openBatch(plan, keyAlias.isPresent());
        final long batchId = batches.save(batch);

        if (batch.id() != null) {
            notifyObservationChange(batchId, batch.routeVersionId(), ObservationChange.Kind.CLEARED);
        }
        return new CollectionPreparation(batchId, keyAlias.isPresent(), keyAlias.orElse(null));
    }

    /** 날짜가 바뀐 뒤 한도가 없어 HTTP를 보내지 못한 준비를 종료한다. */
    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
    public void abandonBeforeSend(final long batchId) {
        CollectionBatch batch = batches.getById(batchId);
        batch.abandonBeforeSend();
        batches.save(batch);
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

        CollectionBatch batch = batches.getById(batchId);
        batch.conclude(collectionResult, receivedAt);
        batches.save(batch);

        return collectionResult;
    }

    private void storeObservations(final long batchId, CollectionResult collectionResult, OffsetDateTime receivedAt) {
        ObservationRows observationRows = collectionResult.observations().orElseThrow();
        logExcludedRows(batchId, observationRows);

        CollectionBatch batch = batches.getById(batchId);
        batch.conclude(collectionResult, receivedAt);
        batch.countRows(observationRows);

        batches.saveObservations(batch, observationRows.storableRows());
        batches.save(batch);

        checkSeatQuality(batchId, batch.routeVersionId(), observationRows.hasPositiveSeats());
        notifyObservationChange(batchId, batch.routeVersionId(), ObservationChange.Kind.STORED);
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

    /**
     * 같은 계획의 수집 배치가 이미 있으면 그 행을 다시 연다.
     * ux_batch_attempt 가 계획 하나당 묶음 하나만 허용하므로 새 행을 넣을 수 없다.
     * 다시 열면 지난 시도의 관측을 지우므로, 예보가 이미 쓴 배치는 다시 열지 않는다.
     */
    private CollectionBatch openBatch(CollectionPlan plan, final boolean reserved) {
        Optional<CollectionBatch> existingBatch = batches.findByPlan(plan);
        if (existingBatch.isEmpty()) {
            return CollectionBatch.start(plan, reserved);
        }

        CollectionBatch batch = existingBatch.orElseThrow();
        Optional<OffsetDateTime> forecastStartedAt = batches.findForecastStart(batch.id());
        if (forecastStartedAt.isPresent()) {
            throw new CollectionInputConfirmedException(batch.id(), forecastStartedAt.get());
        }

        batch.startAttempt(reserved);
        batches.deleteObservationsOf(batch.id());

        return batch;
    }

    private void notifyObservationChange(final long batchId, final long routeVersionId, ObservationChange.Kind kind) {
        ObservationChange observationChange = new ObservationChange(batchId, routeVersionId, kind);

        for (ObservationChangeListener listener : changeListeners) {
            listener.record(observationChange);
        }
    }
}
