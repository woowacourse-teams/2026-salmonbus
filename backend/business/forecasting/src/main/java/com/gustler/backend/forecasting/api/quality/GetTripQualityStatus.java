package com.gustler.backend.forecasting.api.quality;

import java.util.List;

/**
 * 차량별 품질 조사 진행과 해제 가능 여부를 읽는 공개 업무 경계다.
 */
public interface GetTripQualityStatus {
    /**
     * 노선의 조사 진행·완료 상태를 읽는다. 조회 중 조사나 품질 변경은 수행하지 않는다.
     */
    List<TripQualityStatus> status(long routeVersionId);
}
