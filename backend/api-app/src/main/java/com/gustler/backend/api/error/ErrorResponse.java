package com.gustler.backend.api.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

@Schema(requiredProperties = {"code", "message", "requestId"})
public record ErrorResponse(
    @Schema(description = "안정적인 오류 코드")
    ErrorCode code,
    @Schema(description = "사람이 읽는 설명. 클라이언트 분기는 code를 사용")
    String message,
    @Schema(description = "응답 헤더의 X-Request-Id 및 서버 로그와 대응하는 요청 식별자")
    String requestId
) {

    public ErrorResponse {
        Objects.requireNonNull(code, "code는 null일 수 없습니다.");
        Objects.requireNonNull(message, "message는 null일 수 없습니다.");
        Objects.requireNonNull(requestId, "requestId는 null일 수 없습니다.");
    }
}
