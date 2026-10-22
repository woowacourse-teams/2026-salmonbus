package com.gustler.backend.forecasting.domain.quality;

import java.util.List;
import java.util.Optional;

/**
 * 차량 조사의 대기 식별과 단계별 재개 커서를 저장하는 경계다.
 */
public interface TripQualityInvestigationRepository {
    record Key(long routeVersionId, String vehicleId) {
    }
    /**
     * 다음 미완료 조사 키를 찾는다. 대상이 없으면 빈 값이며 조사 실행 자체는 하지 않는다.
     */
    Optional<Key> nextPending();

    /**
     * 노선·차량의 미완료 조사 상태를 찾는다. 진행 중인 조사가 없으면 빈 값이다.
     */
    Optional<TripQualityInvestigation> findPending(long routeVersionId, String vehicleId);

    /**
     * 현재 진행 중인 조사 차량을 읽는다. 같은 차량의 중복 조사 시작을 막는 데 사용한다.
     */
    List<String> activeVehicleIds(long routeVersionId);

    /**
     * 새 조사 시작 상태를 저장한다. 진행 중인 같은 차량의 조사와 직렬화한 경계에서 호출한다.
     */
    void saveStart(TripQualityInvestigation investigation);

    /**
     * 조사 단계·커서·해제 조건을 하나의 상태로 저장한다. 품질 변경과 같은 트랜잭션에서 호출한다.
     */
    void save(TripQualityInvestigation investigation);
}
