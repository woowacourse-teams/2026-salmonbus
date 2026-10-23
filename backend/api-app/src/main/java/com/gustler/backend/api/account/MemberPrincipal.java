package com.gustler.backend.api.account;

import java.security.Principal;
import java.util.UUID;

/**
 * 액세스 토큰으로 인증한 회원.
 *
 * <p>공개 식별자만 담는다. 닉네임처럼 바뀌는 값은 담지 않고 필요할 때 조회한다.
 */
public record MemberPrincipal(UUID publicId) implements Principal {
    @Override
    public String getName() {
        return publicId.toString();
    }
}
