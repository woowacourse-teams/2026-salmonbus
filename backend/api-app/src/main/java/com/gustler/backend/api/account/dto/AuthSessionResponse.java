package com.gustler.backend.api.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** 로그인하지 않았으면 {@code member} 를 싣지 않는다. */
@JsonInclude(Include.NON_NULL)
public record AuthSessionResponse(boolean authenticated, MemberResponse member) {
    public static AuthSessionResponse anonymous() {
        return new AuthSessionResponse(false, null);
    }

    public static AuthSessionResponse of(MemberResponse member) {
        return new AuthSessionResponse(true, member);
    }
}
