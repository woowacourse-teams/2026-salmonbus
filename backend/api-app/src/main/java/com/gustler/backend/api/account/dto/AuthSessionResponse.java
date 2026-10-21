package com.gustler.backend.api.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * 로그인하지 않았으면 {@code member} 를 싣지 않는다.
 *
 * <p>{@code refreshable} 은 액세스 토큰은 없거나 만료됐지만 리프레시 토큰 쿠키가 있어 갱신으로 로그인을 이어 갈 수
 * 있다는 뜻이다. 리프레시 토큰 쿠키는 스크립트가 읽을 수 없어서 클라이언트가 이 값으로 갱신 여부를 판단한다.
 */
@JsonInclude(Include.NON_NULL)
public record AuthSessionResponse(boolean authenticated, boolean refreshable, MemberResponse member) {
    public static AuthSessionResponse anonymous(boolean refreshable) {
        return new AuthSessionResponse(false, refreshable, null);
    }

    public static AuthSessionResponse of(MemberResponse member) {
        return new AuthSessionResponse(true, false, member);
    }
}
