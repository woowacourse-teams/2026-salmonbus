package com.gustler.backend.api.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * 본문으로 갱신을 요청한 클라이언트가 받는 새 토큰.
 *
 * <p>{@code expiresIn} 은 액세스 토큰이 유효한 초다. 리프레시 토큰은 갱신할 때마다 바뀌므로 받은 값으로 바꿔 저장해야 한다.
 */
@Schema(requiredProperties = {"accessToken", "expiresIn", "refreshToken", "refreshTokenExpiresAt"})
public record TokenResponse(
    @Schema(description = "새 액세스 토큰. Authorization: Bearer 헤더에 싣는다")
    String accessToken,
    @Schema(description = "액세스 토큰이 유효한 초", example = "900")
    long expiresIn,
    @Schema(description = "새 리프레시 토큰. 갱신할 때마다 바뀌므로 받은 값으로 바꿔 저장한다")
    String refreshToken,
    @Schema(description = "리프레시 토큰 만료 시각. 이 세대를 발급한 시점부터 30일이고, 처음 로그인한 뒤 180일을 넘지 않는다")
    OffsetDateTime refreshTokenExpiresAt
) {
}
