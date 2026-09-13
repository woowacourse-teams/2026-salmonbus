package com.gustler.backend.api.http;

import java.util.Objects;

public record ErrorResponse(
    ErrorCode code,
    String message,
    String requestId
) {

    public ErrorResponse {
        Objects.requireNonNull(code, "code는 null일 수 없습니다.");
        Objects.requireNonNull(message, "message는 null일 수 없습니다.");
        Objects.requireNonNull(requestId, "requestId는 null일 수 없습니다.");
    }
}
