package com.gustler.backend.api.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 쿠키를 쓰지 않는 클라이언트가 리프레시 토큰을 본문으로 보낸다. 비어 있으면 쿠키를 본다. */
public record RefreshRequest(
    @Schema(description = "쿠키를 쓰지 않는 클라이언트가 보관한 리프레시 토큰. 생략하거나 비어 있으면 __Host-sb_rt 쿠키를 본다")
    String refreshToken
) {
}
