package com.gustler.backend.api.http.login;

import com.gustler.backend.member.api.LoginProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * 앱 로그인 nonce 를 로그인 중 임시 세션에 두고 한 번만 꺼내 쓰게 한다.
 *
 * <p>nonce 는 그 WebView 의 세션에 묶이므로 다른 사람이 자기 계정으로 받은 ID 토큰을 이 WebView 에 넣어도 짝이 맞지
 * 않는다. 수명은 임시 세션 수명(10분)이다. 제공자마다 마지막으로 발급한 값 하나만 둔다. 꺼내면 맞든 틀리든 지운다.
 * 같은 세션의 두 요청이 동시에 꺼내면 둘 다 같은 값을 볼 수 있다. 같은 기기 안의 일이라 막지 않는다.
 */
final class AppLoginNonces {
    private static final String ATTRIBUTE_PREFIX = AppLoginNonces.class.getName() + ".";
    private static final int NONCE_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();

    String issue(HttpServletRequest request, LoginProvider provider) {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        String nonce = ENCODER.encodeToString(bytes);
        request.getSession().setAttribute(attributeOf(provider), nonce);
        return nonce;
    }

    Optional<String> take(HttpServletRequest request, LoginProvider provider) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object stored = session.getAttribute(attributeOf(provider));
        session.removeAttribute(attributeOf(provider));
        if (stored instanceof String nonce) {
            return Optional.of(nonce);
        }
        return Optional.empty();
    }

    private static String attributeOf(LoginProvider provider) {
        return ATTRIBUTE_PREFIX + provider.name();
    }
}
