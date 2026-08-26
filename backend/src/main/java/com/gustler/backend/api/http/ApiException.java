package com.gustler.backend.api.http;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public abstract class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Duration retryAfter;

    protected ApiException(
        final ErrorCode code
    ) {
        this(code, null);
    }

    protected ApiException(
        final ErrorCode code,
        final Duration retryAfter
    ) {
        super(Objects.requireNonNull(code, "code는 null일 수 없습니다.").message());
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public ErrorCode code() {
        return code;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
