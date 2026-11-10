package com.gustler.backend.api.account;

import java.security.Principal;
import java.util.UUID;

/**
 * 액세스 토큰으로 인증한 회원.
 *
 * <p>공개 식별자와 로그인 계통을 담는다. 세션 클레임이 없는 기존 토큰의 sessionId는 null이다.
 */
public record MemberPrincipal(UUID publicId, UUID sessionId) implements Principal {
    @Override
    public String getName() {
        return publicId.toString();
    }
}
