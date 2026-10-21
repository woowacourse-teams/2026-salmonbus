package com.gustler.backend.api.account.dto;

import java.time.OffsetDateTime;

/**
 * 본문으로 갱신을 요청한 클라이언트가 받는 새 토큰.
 *
 * <p>{@code expiresIn} 은 액세스 토큰이 유효한 초다. 리프레시 토큰은 갱신할 때마다 바뀌므로 받은 값으로 바꿔 저장해야 한다.
 */
public record TokenResponse(
    String accessToken,
    long expiresIn,
    String refreshToken,
    OffsetDateTime refreshTokenExpiresAt
) {
}
