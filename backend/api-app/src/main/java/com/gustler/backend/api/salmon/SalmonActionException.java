package com.gustler.backend.api.salmon;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/** 연어행동 조회 요청이 정류장 식별 형식을 벗어났음을 공개 오류로 알린다. */
public class SalmonActionException extends ApiException {
    public SalmonActionException(ErrorCode code) {
        super(code);
    }
}
