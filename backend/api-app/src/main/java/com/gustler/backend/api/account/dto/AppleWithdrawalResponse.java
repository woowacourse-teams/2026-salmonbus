package com.gustler.backend.api.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 브라우저가 이동할 애플 재인증 탈퇴 시작 주소. 안에 든 티켓은 5분 동안만 유효하다. */
@Schema(requiredProperties = {"startUrl"})
public record AppleWithdrawalResponse(
    @Schema(description = "브라우저를 이동시킬 상대 주소. GET /api/auth/apple/start?intent=withdraw&ticket=...이며 "
        + "티켓은 5분 동안 유효하다")
    String startUrl
) {
}
