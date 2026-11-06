package com.gustler.backend.gbis.api;

/**
 * 요청 URL이나 인증값은 전달하지 않고 상태와 서버의 재시도 힌트만 보존한다.
 * {@code rateLimitRemaining}은 포털이 응답 헤더로 알려 준 그 API의 오늘 남은 호출 수이며, 없으면 null이다.
 */
public record GbisHttpResult(
    GbisRawResponse response,
    int httpStatus,
    String retryAfter,
    Integer rateLimitRemaining
) {
    public GbisHttpResult(GbisRawResponse response, final int httpStatus, String retryAfter) {
        this(response, httpStatus, retryAfter, null);
    }
}
