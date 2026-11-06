package com.gustler.backend.api.boarding;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 탑승 후보 조회의 요청·대상 오류를 공개 오류 코드로 알린다.
 */
public class BoardingOptionsException extends ApiException {
    public BoardingOptionsException(ErrorCode code) {
        super(code);
    }
}
