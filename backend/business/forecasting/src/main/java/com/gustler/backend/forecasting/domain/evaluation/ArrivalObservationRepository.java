package com.gustler.backend.forecasting.domain.evaluation;

import java.time.Instant;
import java.util.List;

/**
 * 예보 평가에 필요한 같은 차량의 후속 관측을 읽는 포트.
 *
 * <p>통과 순번은 수집 시 계산해 저장한 값을 그대로 읽는다. 예보 영역에서 운행 상태로
 * 다시 계산하면 같은 규칙을 중복 구현하게 되어 결과가 달라질 수 있다.
 */
public interface ArrivalObservationRepository {

    /** 그 시각보다 뒤인 같은 차량의 관측을 시각 오름차순으로. */
    List<ArrivalCandidate> findAfter(
        long routeVersionId,
        String vehicleId,
        Instant observedAfter,
        int limit
    );
}
