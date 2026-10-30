package com.gustler.backend.api.account;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/** 애플 서버 간 알림을 검증하지 못했음을 알린다. 서명, 발급자, 대상, 형식 중 무엇이 틀렸는지는 응답에서 나누지 않는다. */
public class InvalidAppleNotificationException extends ApiException {
    public InvalidAppleNotificationException() {
        super(ErrorCode.INVALID_REQUEST);
    }
}
