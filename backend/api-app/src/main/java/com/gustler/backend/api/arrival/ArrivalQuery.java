package com.gustler.backend.api.arrival;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 정류장 도착정보 조회의 필터와 참조 판본 조건이다.
 * 원천 정류장 순번과 현재 방문 순번을 혼동하지 않는다.
 */
public record ArrivalQuery(String stationId, String routeId, Long referenceVersionId, Integer stopSequence) {
    public ArrivalQuery {
        if (!isValid(stationId, routeId, referenceVersionId, stopSequence)) {
            throw new Failure(ErrorCode.INVALID_REQUEST);
        }
    }

    private static boolean isValid(
        String stationId,
        String routeId,
        Long referenceVersionId,
        Integer stopSequence
    ) {
        if (!isValidStationId(stationId) || !isValidRouteId(routeId)) {
            return false;
        }
        if (!hasCompleteReference(referenceVersionId, stopSequence)) {
            return false;
        }
        return referenceVersionId == null || isValidReference(routeId, referenceVersionId, stopSequence);
    }

    private static boolean isValidStationId(String stationId) {
        return stationId != null && stationId.matches("[0-9]{9}");
    }

    private static boolean isValidRouteId(String routeId) {
        return routeId == null || routeId.matches("[0-9]{9}");
    }

    private static boolean hasCompleteReference(Long referenceVersionId, Integer stopSequence) {
        return (referenceVersionId == null) == (stopSequence == null);
    }

    private static boolean isValidReference(String routeId, long referenceVersionId, int stopSequence) {
        if (routeId == null || referenceVersionId < 1) {
            return false;
        }
        return stopSequence >= 1;
    }

    public static final class Failure extends ApiException {
        public Failure(ErrorCode code) {
            super(code);
        }
    }
}
