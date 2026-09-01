package com.gustler.backend.api.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ErrorCode {

    INVALID_ROUTE_ID(HttpStatus.BAD_REQUEST, "routeId는 9자리 숫자여야 합니다."),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 노선입니다."),
    MODEL_OUT_OF_SCOPE(HttpStatus.SERVICE_UNAVAILABLE, "활성 모델 번들이 지원하지 않는 노선 판본입니다."),
    NO_RECENT_OBSERVATION(HttpStatus.SERVICE_UNAVAILABLE, "예보 기준으로 사용할 최근 차량 관측이 없습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적인 서버 장애가 발생했습니다."),

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "이 경로에서 지원하지 않는 요청 방식입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(
        HttpStatus status,
        String message
    ) {
        this.status = status;
        this.message = message;
    }

    public static ErrorCode of(
        HttpStatusCode status
    ) {
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ENDPOINT_NOT_FOUND;
        }
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return METHOD_NOT_ALLOWED;
        }
        if (status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)) {
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

    public String message() {
        return message;
    }
}
