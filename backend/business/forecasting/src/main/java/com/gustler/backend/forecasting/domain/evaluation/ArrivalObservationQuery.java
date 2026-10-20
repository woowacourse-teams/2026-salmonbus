package com.gustler.backend.forecasting.domain.evaluation;

import java.time.Instant;
import java.util.List;

/**
 * 예보 평가에 필요한 같은 차량의 후속 관측을 읽는 포트.
 *
 * <p>통과 순번은 수집 시 계산해 저장한 값을 그대로 읽는다. 예보 영역에서 운행 상태로
 * 다시 계산하면 같은 규칙을 중복 구현하게 되어 결과가 달라질 수 있다.
 */
public interface ArrivalObservationQuery {
    /** 기존 조회와 동일한 조건·순서·한도로 필요한 관측 ID를 확인한다. */
    List<Long> findIdsAfter(
        long routeVersionId,
        String vehicleId,
        Instant observedAfter,
        int limit
    );

    /** ID에 해당하는 상세 정보. 호출자는 ID 목록 순서로 결과를 다시 구성한다. */
    List<ArrivalCandidate> findByIds(long routeVersionId, String vehicleId, List<Long> ids);

    /** 그 시각보다 뒤인 같은 차량의 관측을 시각 오름차순으로 읽는다. */
    List<ArrivalCandidate> findAfter(
        long routeVersionId,
        String vehicleId,
        Instant observedAfter,
        int limit
    );
}
