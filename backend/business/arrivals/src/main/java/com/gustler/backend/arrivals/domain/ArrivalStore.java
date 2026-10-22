package com.gustler.backend.arrivals.domain;

import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.time.Instant;
import java.util.Optional;

/**
 * 지원 정류장과 도착정보 수신 스냅샷의 저장·조회·보존 정리 경계다.
 */
public interface ArrivalStore {
    boolean supported(String stationId);

    /**
     * 정류장의 최신 저장 결과를 읽는다. 이력이 없으면 빈 값이며 만료 여부는 호출자가 판단한다.
     */
    Optional<ArrivalSnapshot> latest(String stationId);

    /**
     * 정상 수신 스냅샷과 갱신·만료 시각을 함께 저장한다. 원천 실패에 따른 대기 상태와 구별한다.
     */
    void save(ArrivalSnapshot snapshot);

    /**
     * 기준 시각보다 오래된 결과를 최대 limit건 삭제하고 실제 삭제 건수를 반환한다.
     */
    int purgeExpiredBefore(Instant cutoff, int limit);
}
