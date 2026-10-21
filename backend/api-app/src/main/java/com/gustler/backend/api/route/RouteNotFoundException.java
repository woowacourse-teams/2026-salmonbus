package com.gustler.backend.api.route;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 공개 조회에 필요한 현재 노선을 찾지 못했음을 알린다.
 */
public class RouteNotFoundException extends ApiException {
    public RouteNotFoundException() {
        super(ErrorCode.ROUTE_NOT_FOUND);
    }
}
