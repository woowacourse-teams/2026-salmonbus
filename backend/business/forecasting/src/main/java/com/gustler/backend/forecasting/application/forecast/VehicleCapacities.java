package com.gustler.backend.forecasting.application.forecast;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 예보 시점에 이미 알려진 차량 정원을 찾는 포트다. 그 시점 뒤에 확인된 정원은 돌려주지 않는다. */
@FunctionalInterface
public interface VehicleCapacities {
    /**
     * 차량 번호별 좌석 정원을 돌려준다. 정원을 모르는 차량은 결과에 없다.
     * 판정 기준은 {@code VehicleCapacityCache.findKnownAt}과 같다.
     */
    Map<String, Integer> knownAt(String sourceId, List<String> vehicleIds, Instant at, long observationBatchId);
}
