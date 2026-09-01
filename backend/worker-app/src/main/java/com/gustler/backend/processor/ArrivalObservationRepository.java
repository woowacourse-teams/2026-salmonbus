package com.gustler.backend.processor;

import java.time.Instant;
import java.util.List;

/**
 * 예보를 낸 뒤 그 차량이 남긴 관측을 읽는 포트. 라벨 회수의 재료다.
 *
 * <p>통과 순번은 적재가 계산해 열에 남긴 값을 그대로 읽는다. processor 가 운행 상태에서
 * 다시 유도하면 같은 규칙이 두 벌 생기고 어긋나는 순간을 아무도 못 잡는다.
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
