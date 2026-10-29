package com.gustler.backend.api.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 앱이 Sign in with Apple 로 다시 인증해 받은 인가 코드. */
public record AppleAppWithdrawalRequest(
    @Schema(description = "앱의 Sign in with Apple이 준 authorizationCode. 5분 동안 한 번만 쓸 수 있다",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String authorizationCode
) {
}
