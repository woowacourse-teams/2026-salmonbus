package com.gustler.backend.api.account;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/**
 * 로그인한 회원으로 세션에 저장되는 값.
 *
 * <p>공개 식별자만 담는다. 닉네임처럼 바뀌는 값과 제공자 토큰은 세션에 두지 않고 필요할 때 조회한다.
 * {@link #getName()} 이 세션 표의 {@code PRINCIPAL_NAME} 으로 쓰여, 회원이 탈퇴할 때 그 회원의 모든 세션을 찾을 수 있다.
 */
public record MemberPrincipal(UUID publicId) implements Principal, Serializable {
    @Override
    public String getName() {
        return publicId.toString();
    }
}
