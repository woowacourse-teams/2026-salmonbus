package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.MemberPrincipal;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** 로그인 시작부터 끝까지 세션에 두는 값과 세션 수명. */
final class LoginSessions {
    static final String RETURN_TO_ATTRIBUTE = LoginSessions.class.getName() + ".RETURN_TO";

    /** 로그인 전 세션은 제공자 화면을 다녀오는 동안만 필요하다. 길게 두면 공격자가 빈 세션을 쌓을 수 있다. */
    static final Duration BEFORE_LOGIN = Duration.ofMinutes(10);

    /** 쓰지 않은 채로 이 기간이 지나면 로그인이 풀린다. 쓰는 동안에는 계속 늘어난다. */
    static final Duration AFTER_LOGIN = Duration.ofDays(30);

    private LoginSessions() {
    }

    /**
     * 이 세션이 이미 회원으로 로그인한 세션인지 본다.
     *
     * <p>이런 세션에 로그인 시작이나 실패 콜백이 도달해도 수명을 줄이거나 지우면 안 된다. 두 경로 모두 GET 이라
     * 다른 사이트에서 이동시키는 것만으로 로그인한 사용자를 로그아웃시킬 수 있게 된다.
     */
    static boolean isSignedIn(HttpSession session) {
        Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context instanceof SecurityContext securityContext) {
            Authentication authentication = securityContext.getAuthentication();
            return authentication != null && authentication.getPrincipal() instanceof MemberPrincipal;
        }
        return false;
    }

    static void expireAfter(HttpSession session, Duration idle) {
        session.setMaxInactiveInterval(Math.toIntExact(idle.toSeconds()));
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
