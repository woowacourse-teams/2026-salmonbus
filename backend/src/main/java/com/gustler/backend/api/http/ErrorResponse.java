package com.gustler.backend.api.http;

import java.util.Objects;

public record ErrorResponse(
    ErrorCode code,
    String message,
    String requestId,
    boolean retryable
) {

    public ErrorResponse {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
    }
}
