package com.gustler.backend.api.account.token;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;

/**
 * 요청에서 액세스 토큰과 리프레시 토큰을 꺼낸다.
 *
 * <p>액세스 토큰은 {@code Authorization: Bearer} 헤더를 먼저 보고, 헤더가 없거나 토큰 값이 비어 있으면 쿠키를 본다. 웹과
 * 웹뷰는 쿠키, 헤더를 쓰는 다른 클라이언트는 헤더로 같은 서버를 쓴다. 헤더에 토큰 값이 있으면 그 토큰이 검증에 실패해도
 * 쿠키로 다시 보지 않는다. CSRF 면제 기준과 인증 수단을 같게 두기 위해서다.
 */
public final class BearerTokens {
    private static final String BEARER_PREFIX = "Bearer ";

    private BearerTokens() {
    }

    public static Optional<String> access(HttpServletRequest request) {
        return header(request).or(() -> cookie(request, AuthCookies.ACCESS));
    }

    public static Optional<String> header(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.of(header.substring(BEARER_PREFIX.length()).strip()).filter(token -> !token.isEmpty());
        }
        return Optional.empty();
    }

    public static Optional<String> refreshCookie(HttpServletRequest request) {
        return cookie(request, AuthCookies.REFRESH);
    }

    /**
     * 브라우저가 알아서 실어 보내는 인증 쿠키만으로 인증하는 요청인지. CSRF 방어가 필요한 요청의 기준이다.
     *
     * <p>유효한 형식의 {@code Authorization: Bearer} 헤더는 다른 사이트의 요청이 마음대로 붙일 수 없고, 인증 쿠키가
     * 없으면 도용할 권한도 없다. 둘 중 하나에 해당하는 요청은 CSRF 토큰을 요구하지 않는다. 헤더가 있어도 Bearer 형식이
     * 아니면 인증에 쓰이지 않고 쿠키가 쓰이므로, 면제하지 않는다. 면제 기준과 인증 기준이 어긋나면 쿠키로 인증한 변경
     * 요청이 CSRF 검사를 피할 수 있다.
     */
    public static boolean usesAmbientCredentials(HttpServletRequest request) {
        if (header(request).isPresent()) {
            return false;
        }
        return cookie(request, AuthCookies.ACCESS).isPresent() || refreshCookie(request).isPresent();
    }

    private static Optional<String> cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
            .filter(cookie -> name.equals(cookie.getName()))
            .map(Cookie::getValue)
            .filter(value -> !value.isEmpty())
            .findFirst();
    }
}
