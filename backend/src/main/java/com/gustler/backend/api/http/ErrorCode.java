package com.gustler.backend.api.http;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_ROUTE_ID(HttpStatus.BAD_REQUEST, false, "routeId는 9자리 숫자여야 합니다."),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, false, "등록되지 않은 노선입니다."),
    MODEL_OUT_OF_SCOPE(HttpStatus.SERVICE_UNAVAILABLE, true, "활성 모델 번들이 지원하지 않는 노선 판본입니다."),
    NO_RECENT_OBSERVATION(HttpStatus.SERVICE_UNAVAILABLE, true, "예보 기준으로 사용할 최근 차량 관측이 없습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "일시적인 서버 장애가 발생했습니다.");

    private final HttpStatus status;
    private final boolean retryable;
    private final String message;

    ErrorCode(
        final HttpStatus status,
        final boolean retryable,
        final String message
    ) {
        this.status = status;
        this.retryable = retryable;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public String message() {
        return message;
    }
}
