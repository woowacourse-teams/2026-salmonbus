package com.gustler.backend.api.arrival;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 정류장 도착정보 조회의 필터와 참조 판본 조건이다.
 * 원천 정류장 순번과 현재 방문 순번을 혼동하지 않는다.
 */
public record ArrivalQuery(String stationId, String routeId, Long referenceVersionId, Integer stopSequence) {
    public ArrivalQuery {
        if (stationId == null || !stationId.matches("[0-9]{9}")
            || (routeId != null && !routeId.matches("[0-9]{9}"))
            || ((referenceVersionId == null) != (stopSequence == null))
            || (referenceVersionId != null && (routeId == null || referenceVersionId < 1 || stopSequence < 1))) {
            throw new Failure(ErrorCode.INVALID_REQUEST);
        }
    }

    public static final class Failure extends ApiException {
        public Failure(ErrorCode code) {
            super(code);
        }
    }
}
