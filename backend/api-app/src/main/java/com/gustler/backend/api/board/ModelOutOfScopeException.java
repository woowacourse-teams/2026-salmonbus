package com.gustler.backend.api.board;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 요청한 노선이 현재 활성 모델의 지원 범위에 없음을 API 오류 경계에 알린다.
 */
public class ModelOutOfScopeException extends ApiException {
    public ModelOutOfScopeException() {
        super(ErrorCode.MODEL_OUT_OF_SCOPE);
    }
}
