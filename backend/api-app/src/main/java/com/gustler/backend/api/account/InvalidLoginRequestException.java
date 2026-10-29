package com.gustler.backend.api.account;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/** 앱 로그인·탈퇴 요청 본문에 필요한 값이 없음을 알린다. */
public class InvalidLoginRequestException extends ApiException {
    public InvalidLoginRequestException() {
        super(ErrorCode.INVALID_REQUEST);
    }
}
