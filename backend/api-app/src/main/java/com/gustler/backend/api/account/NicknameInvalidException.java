package com.gustler.backend.api.account;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.member.api.NicknameViolation;

/** 닉네임 규칙을 어겼음을 알린다. 어긴 규칙마다 오류 코드가 다르다. */
public class NicknameInvalidException extends ApiException {
    public NicknameInvalidException(NicknameViolation violation) {
        super(codeOf(violation));
    }

    private static ErrorCode codeOf(NicknameViolation violation) {
        return switch (violation) {
            case LENGTH -> ErrorCode.NICKNAME_LENGTH;
            case CHARACTERS -> ErrorCode.NICKNAME_CHARACTERS;
            case RESERVED -> ErrorCode.NICKNAME_RESERVED;
        };
    }
}
