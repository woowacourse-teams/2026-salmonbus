package com.gustler.backend.api.route;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 요청한 노선 참조 판본의 형식이 올바르지 않음을 API 오류 경계에 알린다.
 */
public class InvalidReferenceVersionException extends ApiException {
    public InvalidReferenceVersionException() {
        super(ErrorCode.INVALID_REQUEST);
    }
}
