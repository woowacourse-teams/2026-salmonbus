package com.gustler.backend.api.account;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/** 다른 회원이 이미 쓰는 닉네임임을 알린다. 영문 대소문자는 구분하지 않는다. */
public class NicknameTakenException extends ApiException {
    public NicknameTakenException() {
        super(ErrorCode.NICKNAME_TAKEN);
    }
}
