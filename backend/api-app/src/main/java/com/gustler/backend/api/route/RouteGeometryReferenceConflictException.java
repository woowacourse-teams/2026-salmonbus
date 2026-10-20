package com.gustler.backend.api.route;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 요청한 참조 판본이 현재 지도 판본과 다름을 알린다.
 * 다른 판본의 방문 순번을 재사용하지 않는다.
 */
public class RouteGeometryReferenceConflictException extends ApiException {
    public RouteGeometryReferenceConflictException() {
        super(ErrorCode.REFERENCE_VERSION_CONFLICT);
    }
}
