package com.gustler.backend.api.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ErrorCode {

    INVALID_ROUTE_ID(HttpStatus.BAD_REQUEST, false, "routeId는 9자리 숫자여야 합니다."),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, false, "등록되지 않은 노선입니다."),
    MODEL_OUT_OF_SCOPE(HttpStatus.SERVICE_UNAVAILABLE, true, "활성 모델 번들이 지원하지 않는 노선 판본입니다."),
    NO_RECENT_OBSERVATION(HttpStatus.SERVICE_UNAVAILABLE, true, "예보 기준으로 사용할 최근 차량 관측이 없습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "일시적인 서버 장애가 발생했습니다."),

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, false, "요청 형식이 올바르지 않습니다."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, false, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, false, "이 경로에서 지원하지 않는 요청 방식입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false, "요청을 처리하지 못했습니다.");

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

    public static ErrorCode of(
        final HttpStatusCode status
    ) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return ENDPOINT_NOT_FOUND;
        }
        if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return METHOD_NOT_ALLOWED;
        }
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return SERVICE_UNAVAILABLE;
        }
        if (status.is4xxClientError()) {
            return INVALID_REQUEST;
        }
        return INTERNAL_ERROR;
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
