package com.gustler.backend.member.api;

import java.time.OffsetDateTime;

/**
 * 발급한 리프레시 토큰.
 *
 * <p>토큰은 서버가 저장하지 않고 서버 비밀로 서명해 만든 값이다. 같은 계통과 세대에서는 언제나 같은 값이 나온다.
 * 테이블에는 계통의 세대와 시각만 있다.
 */
public record IssuedRefreshToken(String token, OffsetDateTime expiresAt) {
}
