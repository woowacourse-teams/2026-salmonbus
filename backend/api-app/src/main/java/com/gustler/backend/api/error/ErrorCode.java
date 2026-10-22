package com.gustler.backend.api.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * API 오류의 공개 코드·메시지·상태 대응이다.
 * 기존 코드 이름과 값은 클라이언트 계약으로 유지한다.
 */
public enum ErrorCode {
    INVALID_ROUTE_ID(HttpStatus.BAD_REQUEST, "routeId는 9자리 숫자여야 합니다."),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 노선입니다."),
    STATION_NOT_FOUND(HttpStatus.NOT_FOUND, "지원하지 않는 정류장입니다."),
    REFERENCE_VERSION_CONFLICT(HttpStatus.CONFLICT, "노선 판본과 정류장 방문이 일치하지 않습니다."),
    MODEL_OUT_OF_SCOPE(HttpStatus.SERVICE_UNAVAILABLE, "활성 모델 번들이 지원하지 않는 노선 판본입니다."),
    NO_RECENT_OBSERVATION(HttpStatus.SERVICE_UNAVAILABLE, "예보 기준으로 사용할 최근 차량 관측이 없습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적인 서버 장애가 발생했습니다."),

    FAVORITE_GROUP_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "회원은 즐겨찾기 그룹을 3개까지만 만들 수 있습니다."),
    FAVORITE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "즐겨찾기 그룹에는 즐겨찾기를 2개까지만 등록할 수 있습니다."),
    INVALID_FAVORITE_GROUP_NAME(HttpStatus.BAD_REQUEST, "즐겨찾기 그룹 이름은 공백을 제외하고 1자 이상 10자 이하여야 합니다."),
    FAVORITE_GROUP_NAME_CONFLICT(HttpStatus.CONFLICT, "같은 이름의 즐겨찾기 그룹이 이미 있습니다."),
    FAVORITE_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "즐겨찾기 그룹을 찾을 수 없습니다."),
    LAST_FAVORITE_GROUP(HttpStatus.CONFLICT, "마지막 즐겨찾기 그룹은 삭제할 수 없습니다."),
    INVALID_FAVORITE_GROUP_ORDER(HttpStatus.BAD_REQUEST, "현재 즐겨찾기 그룹 ID를 빠짐없이 한 번씩 보내야 합니다."),
    INVALID_FAVORITE_ORDER(HttpStatus.BAD_REQUEST, "현재 즐겨찾기 ID를 빠짐없이 한 번씩 보내야 합니다."),
    FAVORITE_TARGET_AMBIGUOUS(HttpStatus.CONFLICT, "현재 노선에서 같은 정류장 방문을 하나로 정할 수 없습니다."),
    FAVORITE_TARGET_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "현재 노선에서 승차할 수 없는 정류장입니다."),

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "이 경로에서 지원하지 않는 요청 방식입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다."),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    CSRF_REJECTED(HttpStatus.BAD_REQUEST, "요청을 확인하지 못했습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요."),

    NICKNAME_LENGTH(HttpStatus.BAD_REQUEST, "닉네임은 2자 이상 10자 이하여야 합니다."),
    NICKNAME_CHARACTERS(HttpStatus.BAD_REQUEST, "닉네임은 한글, 영문, 숫자만 쓸 수 있습니다."),
    NICKNAME_RESERVED(HttpStatus.BAD_REQUEST, "쓸 수 없는 닉네임입니다."),
    NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    REAUTH_REQUIRED(HttpStatus.CONFLICT, "애플 계정으로 한 번 더 인증해야 탈퇴할 수 있습니다.");

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
        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            return UNAUTHENTICATED;
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
