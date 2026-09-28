package com.gustler.backend.observations.application;

import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.ObservationReply;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.domain.ObservationBatchReservation;
import com.gustler.backend.observations.domain.ObservationRepository;
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
    private final ObservationRepository observations;
    private final ObservationLoader loader;

    public ObservationBatchLedger(ApiCallQuota callQuota, ObservationRepository observations,
                                  ObservationLoader loader) {
        this.callQuota = callQuota;
        this.observations = observations;
        this.loader = loader;
    }

    /** 수집 계획과 호출 횟수를 함께 예약한다. 실패하면 두 변경 모두 롤백한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ObservationBatchReservation reserve(CollectionPlan plan, OffsetDateTime reservedAt) {
        Optional<String> keyAlias = callQuota.reserveLocation(reservedAt);
        if (keyAlias.isPresent()) {
            return new ObservationBatchReservation(observations.openReserved(plan), true, keyAlias.get());
        }
        return new ObservationBatchReservation(observations.openNotReserved(plan), false, null);
    }

    /** 한국 자정을 지났으면 새 날짜의 한도도 확보한 뒤 전송 사실을 별도로 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDispatching(final long batchId, OffsetDateTime reservedAt,
                                   OffsetDateTime requestedAt, String keyAlias) {
        if (!callQuota.ensureLocationReservation(keyAlias, reservedAt, requestedAt)) {
            observations.abandonBeforeSend(batchId);
            return false;
        }
        observations.markDispatching(batchId, requestedAt);
        return true;
    }

    @Transactional
    public void abandonBeforeSend(final long batchId) {
        observations.abandonBeforeSend(batchId);
    }

    /** 관측 저장과 수집 완료, 필요한 품질 조사 등록을 한 트랜잭션에서 수행한다. */
    @Transactional
    public ObservationResponse conclude(final long batchId, ObservationReply reply, OffsetDateTime receivedAt) {
        ObservationResponse response = reply.interpret(receivedAt);
        if (response.observations().isPresent()) {
            loader.load(batchId, response.conclusion(), response.observations().orElseThrow(), receivedAt);
        } else {
            observations.concludeWithoutRows(batchId, response.conclusion(), receivedAt);
        }
        return response;
    }
}
