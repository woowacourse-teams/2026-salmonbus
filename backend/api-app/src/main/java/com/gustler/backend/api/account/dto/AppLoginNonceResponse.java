package com.gustler.backend.api.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 앱 로그인 한 번에만 쓸 수 있는 nonce. */
public record AppLoginNonceResponse(
    @Schema(description = "제공자 SDK에 넘길 nonce. 애플은 이 값의 SHA-256(소문자 16진수)을, 카카오는 이 값 그대로를 넘긴다. "
        + "10분 동안 한 번만 쓸 수 있고 같은 제공자로 다시 받으면 앞의 값은 못 쓴다",
        example = "q9V8Zf1xT0yY2sJm3kQwR5uE7iO4pA6dL1nB8cV0hGs")
    String nonce
) {
}
