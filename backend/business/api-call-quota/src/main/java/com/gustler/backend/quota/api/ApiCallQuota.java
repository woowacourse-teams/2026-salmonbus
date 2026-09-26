package com.gustler.backend.quota.api;

import java.time.OffsetDateTime;

/**
 * 외부 API를 호출하기 전에 해당 서비스의 한국 날짜별 한도를 예약한다.
 * 기존 트랜잭션이 있으면 참여하고, 없으면 예약만 짧은 트랜잭션으로 확정한다.
 */
public interface ApiCallQuota {

    boolean reserveLocation(OffsetDateTime requestedAt);

    /**
     * 이미 예약한 위치 조회를 전송할 때 한국 날짜가 바뀌었다면 새 날짜의 한도를 예약한다.
     * 호출자는 전송 대기 상태 확인과 상태 변경을 같은 트랜잭션에서 수행해야 한다.
     * 전날 확정한 예약은 환급하지 않는다.
     */
    boolean ensureLocationReservation(OffsetDateTime reservedAt, OffsetDateTime requestedAt);

    /** 노선 조회에 필요한 호출을 전부 예약하거나 하나도 예약하지 않는다. */
    boolean reserveRouteCatalog(OffsetDateTime requestedAt, int calls);
}
