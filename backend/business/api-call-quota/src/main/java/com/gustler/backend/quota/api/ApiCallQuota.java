package com.gustler.backend.quota.api;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 외부 API를 호출하기 전에 해당 서비스의 한국 날짜별 한도를 예약한다.
 * 기존 트랜잭션이 있으면 참여하고, 없으면 예약만 짧은 트랜잭션으로 확정한다.
 */
public interface ApiCallQuota {
    /** 위치정보 호출 한 번을 예약하고 키 별칭을 반환한다. 모든 키가 소진되면 빈 값이다. */
    Optional<String> reserveLocation(OffsetDateTime requestedAt);

    /**
     * 이미 예약한 위치 조회를 전송할 때 한국 날짜가 바뀌었다면 새 날짜의 한도를 예약한다.
     * 호출자는 전송 대기 상태 확인과 상태 변경을 같은 트랜잭션에서 수행해야 한다.
     * 전날 확정한 예약은 환급하지 않는다.
     */
    boolean ensureLocationReservation(String keyAlias, OffsetDateTime reservedAt, OffsetDateTime requestedAt);

    /** 해당 한국 날짜의 키를 소진 처리하고 변경 전 예약 횟수를 반환한다. 장부가 없으면 빈 값이다. */
    Optional<Integer> excludeLocationKey(String keyAlias, OffsetDateTime requestedAt);

    /** 노선 조회에 필요한 호출을 전부 예약하거나 하나도 예약하지 않는다. */
    boolean reserveRouteCatalog(OffsetDateTime requestedAt, int calls);
}
