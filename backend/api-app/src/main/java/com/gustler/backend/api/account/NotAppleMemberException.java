package com.gustler.backend.api.account;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/** 애플 재인증 탈퇴는 애플 회원만 시작할 수 있음을 알린다. 카카오 회원은 {@code DELETE /api/me} 로 탈퇴한다. */
public class NotAppleMemberException extends ApiException {
    public NotAppleMemberException() {
        super(ErrorCode.INVALID_REQUEST);
    }
}
