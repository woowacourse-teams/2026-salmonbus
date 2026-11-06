package com.gustler.backend.gbis.api;

/**
 * 요청 URL이나 인증값은 전달하지 않고 상태와 서버의 재시도 힌트만 보존한다.
 * {@code rateLimitRemaining}은 포털이 응답 헤더로 알려 준 그 API의 오늘 남은 호출 수이며, 없으면 null이다.
 * Capture bytes must pass endpoint validation before retention.
 */
public record GbisHttpResult(
    GbisRawResponse response,
    int httpStatus,
    String retryAfter,
    Integer rateLimitRemaining,
    GbisResponseCapture capture
) {
    public GbisHttpResult(GbisRawResponse response, int httpStatus, String retryAfter) {
        this(response, httpStatus, retryAfter, null, GbisResponseCapture.NONE);
    }

    public GbisHttpResult(GbisRawResponse response, int httpStatus, String retryAfter, Integer rateLimitRemaining) {
        this(response, httpStatus, retryAfter, rateLimitRemaining, GbisResponseCapture.NONE);
    }

    public GbisHttpResult(GbisRawResponse response, int httpStatus, String retryAfter, GbisResponseCapture capture) {
        this(response, httpStatus, retryAfter, null, capture);
    }

    public GbisHttpResult {
        if (capture == null) capture = GbisResponseCapture.NONE;
    }

    @Override public String toString() {
        return "GbisHttpResult[httpStatus=" + httpStatus + ", responseType="
            + (response == null ? "NONE" : response.getClass().getSimpleName())
            + ", rateLimitRemaining=" + rateLimitRemaining
            + ", capture=" + capture + "]";
    }
}
