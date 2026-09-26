package com.gustler.backend.quota.application;

import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.quota.api.CallQuotaPolicy;
import com.gustler.backend.quota.domain.CallQuota;
import com.gustler.backend.quota.domain.CallQuotaRepository;
import com.gustler.backend.quota.domain.DailyCallQuota;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 호출을 보내기 전에 하루 한도에서 호출 횟수를 예약한다.
 *
 * <p>하루 한도는 10,000회이고 두 노선을 90초 간격으로 수집한 실측 호출량은 8,194회다.
 * 한도의 82퍼센트를 사용하므로 호출을 보낸 뒤 집계해서는 초과를 막기 어렵다.
 *
 * <p>기존 트랜잭션이 있으면 참여한다. ObservationBatchLedger가 여는 트랜잭션에서 예약과
 * batch를 함께 커밋해야 batch INSERT 실패로 예약만 남는 일을 막을 수 있다.
 * 노선 조회처럼 호출자 트랜잭션이 없으면 예약만 짧게 커밋한 뒤 상류를 호출한다.
 *
 * <p>커밋한 예약은 프로세스가 종료돼도 취소하지 않는다.
 * 전송 여부가 불확실한 호출의 예약을 취소하면 다음 시도가 같은 한도를 다시 사용한다.
 */
@Component
public class CallQuotaLedger implements ApiCallQuota {

    private static final int ONE_CALL = 1;

    private final CallQuotaRepository callQuotaRepository;
    private final CallQuotaPolicy policy;

    public CallQuotaLedger(
        CallQuotaRepository callQuotaRepository,
        CallQuotaPolicy policy
    ) {
        this.callQuotaRepository = callQuotaRepository;
        this.policy = policy;
    }

    /** 호출 한 번을 예약한다. 위치정보는 한 batch가 호출 한 번이다. */
    @Override
    @Transactional
    public boolean reserveLocation(
        OffsetDateTime requestedAt
    ) {
        return reserveCalls(CallQuota.BUS_LOCATION, requestedAt, ONE_CALL);
    }

    /** 요청한 호출 횟수를 예약한다. 노선정보는 한 노선을 읽는 데 호출 두 번이 든다. */
    @Override
    @Transactional
    public boolean reserveRouteCatalog(
        OffsetDateTime requestedAt,
        final int calls
    ) {
        return reserveCalls(CallQuota.BUS_ROUTE, requestedAt, calls);
    }

    private boolean reserveCalls(
        CallQuota quota,
        OffsetDateTime requestedAt,
        final int calls
    ) {
        return DailyCallQuota.at(quota, requestedAt, policy.limitOf(quota))
            .reservationFor(calls)
            .map(callQuotaRepository::reserve)
            .orElse(false);
    }

    /**
     * 전송 날짜의 호출 예약이 있는지 확인하고 없으면 새로 예약한다.
     *
     * <p>예약 시각과 실제 전송 시각 사이에 한국 자정이 지날 수 있다.
     * 23:59:59에 예약하고 00:00:00에 보내면 상류는 다음 날 호출로 집계하지만 예약은 전날에 남는다.
     * 다음 날 한도에서도 차감하지 않으면 상류 한도를 초과한다.
     */
    @Override
    @Transactional
    public boolean ensureLocationReservation(
        OffsetDateTime reservedAt,
        OffsetDateTime dispatchAt
    ) {
        if (DailyCallQuota.at(CallQuota.BUS_LOCATION, reservedAt, policy.limitOf(CallQuota.BUS_LOCATION))
            .covers(dispatchAt)) {
            return true;
        }
        return reserveCalls(CallQuota.BUS_LOCATION, dispatchAt, ONE_CALL);
    }
}
