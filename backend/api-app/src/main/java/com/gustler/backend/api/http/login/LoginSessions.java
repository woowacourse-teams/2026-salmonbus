package com.gustler.backend.api.http.login;

import jakarta.servlet.http.HttpSession;

/**
 * 로그인 시작부터 끝까지만 쓰는 임시 세션에 두는 값.
 *
 * <p>세션은 제공자 화면을 다녀오는 동안에만 필요하고 로그인이 끝나면 지운다. 로그인 상태는 세션이 아니라 토큰이 가진다.
 * 수명은 서버 설정({@code server.servlet.session.timeout})의 10분이다.
 */
final class LoginSessions {
    static final String RETURN_TO_ATTRIBUTE = LoginSessions.class.getName() + ".RETURN_TO";

    private LoginSessions() {
    }

    static String takeReturnTo(HttpSession session) {
        Object stored = session.getAttribute(RETURN_TO_ATTRIBUTE);
        session.removeAttribute(RETURN_TO_ATTRIBUTE);
        if (stored instanceof String returnTo) {
            return ReturnTo.sanitize(returnTo);
        }
        return ReturnTo.HOME;
    }
}
