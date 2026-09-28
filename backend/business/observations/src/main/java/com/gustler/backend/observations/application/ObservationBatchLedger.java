package com.gustler.backend.observations.application;

import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.CollectionBatchRepository;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.ObservationReply;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.domain.ObservationBatchReservation;
import com.gustler.backend.quota.api.ApiCallQuota;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 외부 호출 전 예약과 전송 기록, 호출 후 결과 저장의 트랜잭션을 관리한다. */
@Component
public class ObservationBatchLedger {
    private final ApiCallQuota callQuota;
    private final CollectionBatchRepository batches;
    private final ObservationLoader loader;

    public ObservationBatchLedger(ApiCallQuota callQuota, CollectionBatchRepository batches,
                                  ObservationLoader loader) {
        this.callQuota = callQuota;
        this.batches = batches;
        this.loader = loader;
    }

    /** 수집 계획과 호출 횟수를 함께 예약한다. 실패하면 두 변경 모두 롤백한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ObservationBatchReservation reserve(CollectionPlan plan, OffsetDateTime reservedAt) {
        Optional<String> keyAlias = callQuota.reserveLocation(reservedAt);
        if (keyAlias.isPresent()) {
            return new ObservationBatchReservation(open(plan, true), true, keyAlias.get());
        }
        return new ObservationBatchReservation(open(plan, false), false, null);
    }

    /** 한국 자정을 지났으면 새 날짜의 한도도 확보한 뒤 전송 사실을 별도로 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDispatching(final long batchId, OffsetDateTime reservedAt,
                                   OffsetDateTime requestedAt, String keyAlias) {
        if (!callQuota.ensureLocationReservation(keyAlias, reservedAt, requestedAt)) {
            abandon(batchId);
            return false;
        }
        CollectionBatch batch = batches.getById(batchId);
        batch.dispatch(requestedAt);
        batches.save(batch);
        return true;
    }

    @Transactional
    public void abandonBeforeSend(final long batchId) {
        abandon(batchId);
    }

    /** 관측 저장과 수집 완료, 필요한 품질 조사 등록을 한 트랜잭션에서 수행한다. */
    @Transactional
    public ObservationResponse conclude(final long batchId, ObservationReply reply, OffsetDateTime receivedAt) {
        ObservationResponse response = reply.interpret(receivedAt);
        if (response.observations().isPresent()) {
            loader.load(batchId, response.conclusion(), response.observations().orElseThrow(), receivedAt);
        } else {
            CollectionBatch batch = batches.getById(batchId);
            batch.conclude(response.conclusion(), receivedAt);
            batches.save(batch);
        }
        return response;
    }

    /**
     * 같은 계획의 판이 이미 있으면 그 행을 다시 연다.
     * ux_batch_attempt 가 계획 하나에 묶음 하나를 강제해서 새로 넣으면 들어가지 않는다.
     */
    private long open(CollectionPlan plan, final boolean reserved) {
        var existing = batches.findByPlan(plan);
        if (existing.isEmpty()) {
            return batches.save(CollectionBatch.start(plan, reserved));
        }
        CollectionBatch batch = existing.orElseThrow();
        batch.startAttempt(reserved);
        batches.deleteObservationsOf(batch.id());
        return batches.save(batch);
    }

    private void abandon(final long batchId) {
        CollectionBatch batch = batches.getById(batchId);
        batch.abandonBeforeSend();
        batches.save(batch);
    }
}
