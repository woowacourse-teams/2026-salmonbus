package com.gustler.backend.api.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 닉네임이 없거나 비어 있으면 길이 규칙 위반으로 처리한다. */
@Schema(requiredProperties = {"nickname"})
public record UpdateNicknameRequest(
    @Schema(description = "새 닉네임. 앞뒤 공백을 지운 뒤 2자 이상 10자 이하의 한글·영문·숫자", example = "느긋한판교역7")
    String nickname
) {
}
