package com.gustler.backend.api.route;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 노선 공개 식별자의 형식이 올바르지 않음을 API 오류 경계에 알린다.
 */
public class InvalidRouteIdException extends ApiException {
    public InvalidRouteIdException() {
        super(ErrorCode.INVALID_ROUTE_ID);
    }
}
