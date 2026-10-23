package com.gustler.backend.gbis.api;

/** 요청 URL이나 인증값은 전달하지 않고 상태와 서버의 재시도 힌트만 보존한다. */
public record GbisHttpResult(GbisRawResponse response, int httpStatus, String retryAfter) {
}
