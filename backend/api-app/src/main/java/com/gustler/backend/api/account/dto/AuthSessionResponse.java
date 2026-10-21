package com.gustler.backend.api.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 로그인하지 않았으면 {@code member} 를 싣지 않는다.
 *
 * <p>{@code refreshable} 은 액세스 토큰은 없거나 만료됐지만 리프레시 토큰 쿠키가 있어 갱신으로 로그인을 이어 갈 수
 * 있다는 뜻이다. 리프레시 토큰 쿠키는 스크립트가 읽을 수 없어서 클라이언트가 이 값으로 갱신 여부를 판단한다.
 */
@JsonInclude(Include.NON_NULL)
@Schema(requiredProperties = {"authenticated", "refreshable"})
public record AuthSessionResponse(
    @Schema(description = "유효한 액세스 토큰이 있고 회원이 있으면 true")
    boolean authenticated,
    @Schema(description = "authenticated=false이고 리프레시 토큰 쿠키가 있으면 true. POST /api/auth/refresh로 이어 갈 수 "
        + "있다는 뜻이며 토큰이 유효한지는 확인하지 않는다. authenticated=true면 항상 false")
    boolean refreshable,
    @Schema(description = "로그인한 회원. authenticated=false면 필드 생략")
    MemberResponse member
) {
    public static AuthSessionResponse anonymous(boolean refreshable) {
        return new AuthSessionResponse(false, refreshable, null);
    }

    public static AuthSessionResponse of(MemberResponse member) {
        return new AuthSessionResponse(true, false, member);
    }
}
