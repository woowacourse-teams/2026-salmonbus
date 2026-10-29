package com.gustler.backend.api.docs;

/** 로그인 API 공개 문서에서 여러 operation 이 함께 쓰는 설명 문구다. */
public final class AuthApiDocs {
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    public static final String CSRF_HEADER_DESCRIPTION =
        "__Host-XSRF-TOKEN 쿠키 값. 인증 쿠키가 실려 있고 Authorization: Bearer 헤더가 없으면 필수";

    public static final String ACCESS_TOKEN_RULE =
        "액세스 토큰은 `Authorization: Bearer` 헤더를 먼저 보고, 헤더가 없거나 토큰 값이 비어 있으면 `__Host-sb_at` 쿠키를 봅니다. "
            + "헤더에 토큰 값이 있으면 그 토큰이 검증에 실패해도 쿠키를 보지 않습니다.";

    public static final String CSRF_RULE = "인증 쿠키(`__Host-sb_at`·`__Host-sb_rt`)가 실린 요청은 "
        + "`Authorization: Bearer` 헤더가 없으면 `X-XSRF-TOKEN` 헤더에 `__Host-XSRF-TOKEN` 쿠키 값을 실어야 합니다.";

    public static final String APP_LOGIN_CSRF_RULE = "앱 로그인 요청은 인증 쿠키가 없어도 `X-XSRF-TOKEN` 헤더에 "
        + "`__Host-XSRF-TOKEN` 쿠키 값을 실어야 합니다. `Origin` 헤더가 있으면 사이트 주소와 같아야 합니다.";

    public static final String APP_LOGIN_CSRF_HEADER_DESCRIPTION = "__Host-XSRF-TOKEN 쿠키 값. 앱 로그인 요청에는 늘 필수";

    public static final String NO_STORE = "no-store";

    public static final String CLEARED_COOKIES = "__Host-sb_at과 __Host-sb_rt를 Max-Age=0으로 지움";

    private AuthApiDocs() {
    }
}
