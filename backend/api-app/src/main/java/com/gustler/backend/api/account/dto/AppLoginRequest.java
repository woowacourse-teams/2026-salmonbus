package com.gustler.backend.api.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 앱이 제공자 SDK 로 받아 웹에 넘긴 ID 토큰. nonce 는 서버가 임시 세션에서 읽으므로 본문에 싣지 않는다. */
public record AppLoginRequest(
    @Schema(description = "제공자 SDK가 발급한 ID 토큰(JWT)", requiredMode = Schema.RequiredMode.REQUIRED)
    String idToken
) {
}
