package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.salmon.SalmonActionException;

/** 판정을 요청한 현재 정류장 방문. 형식 검사는 여기서 끝나고 판정은 이 값을 그대로 쓴다. */
public record SalmonStop(String routeId, String stationId, int stopSequence) {
    public SalmonStop {
        new RouteId(routeId);
        if (stationId == null || !stationId.matches("[0-9]{1,20}") || stopSequence < 1) {
            throw new SalmonActionException(ErrorCode.INVALID_REQUEST);
        }
    }
}
