package com.gustler.backend.api.arrival.application;

import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * 현재 정류장 방문과 최근 도착정보 수신 결과를 읽는 API 저장소 경계다.
 * 수집 요청이나 저장은 수행하지 않는다.
 */
public interface ArrivalReadRepository {
    /**
     * 정류장의 현재 노선 방문 목록을 읽는다. 같은 노선의 반복 방문은 순번으로 구별한다.
     */
    List<ArrivalVisit> visits(String stationId);

    /**
     * 저장된 최신 수신 결과를 읽는다. 저장 이력이 없으면 빈 값을 반환하며 원천 수집을 요청하지 않는다.
     */
    Optional<ArrivalSnapshot> latest(String stationId);
}
