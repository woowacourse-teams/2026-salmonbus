package com.gustler.backend.api.http.login;

import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;

/**
 * 로그인 시작부터 끝까지만 쓰는 임시 세션에 두는 값.
 *
 * <p>세션은 제공자 화면을 다녀오는 동안에만 필요하고 로그인이 끝나면 지운다. 로그인 상태는 세션이 아니라 토큰이 가진다.
 * 수명은 서버 설정({@code server.servlet.session.timeout})의 10분이다.
 */
final class LoginSessions {
    static final String RETURN_TO_ATTRIBUTE = LoginSessions.class.getName() + ".RETURN_TO";

    /** 탈퇴하려고 다시 인증하는 회원의 공개 식별자. 이 값이 있으면 콜백은 로그인이 아니라 탈퇴로 처리한다. */
    static final String WITHDRAWING_MEMBER_ATTRIBUTE = LoginSessions.class.getName() + ".WITHDRAWING_MEMBER";

    private LoginSessions() {
    }

    static Optional<UUID> withdrawingMember(HttpSession session) {
        if (session.getAttribute(WITHDRAWING_MEMBER_ATTRIBUTE) instanceof String memberId) {
            return Optional.of(UUID.fromString(memberId));
        }
        return Optional.empty();
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
