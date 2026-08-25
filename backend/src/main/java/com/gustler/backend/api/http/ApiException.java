package com.gustler.backend.api.http;

import java.util.Objects;

public abstract class ApiException extends RuntimeException {

    private final ErrorCode code;

    protected ApiException(
        final ErrorCode code
    ) {
        super(Objects.requireNonNull(code, "code는 null일 수 없습니다.").message());
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
