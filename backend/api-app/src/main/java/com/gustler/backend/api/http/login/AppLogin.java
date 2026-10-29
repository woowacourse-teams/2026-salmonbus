package com.gustler.backend.api.http.login;

import com.gustler.backend.member.api.LoginProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 앱 네이티브 로그인이 켜진 제공자와 그 검증기, 앱 애플 탈퇴를 묶고, 앱 로그인 요청의 출처를 확인한다.
 *
 * <p>경로는 {@code POST /api/auth/{provider}/native/nonce} 와 {@code POST /api/auth/{provider}/native} 다. 이 두
 * 요청은 인증 쿠키가 없어도 CSRF 토큰을 요구한다({@link #isAppLoginRequest}). 로그인 전 요청이라 쿠키 기준으로는 면제되지만,
 * 다른 사이트가 공격자 계정의 토큰으로 사용자를 로그인시키는 일(로그인 CSRF)을 막아야 하기 때문이다.
 *
 * <p>{@code Origin} 헤더가 있으면 사이트 주소({@code auth.public-base-url})의 출처와 같아야 한다. 없으면 보지 않는다.
 */
public final class AppLogin {
    private static final Pattern APP_LOGIN_PATH = Pattern.compile("/api/auth/[a-z]+/native(/nonce)?");

    private final Map<String, Provider> providers;
    private final Optional<AppleAppWithdrawal> appleWithdrawal;
    private final String siteOrigin;

    AppLogin(Map<String, Provider> providers, Optional<AppleAppWithdrawal> appleWithdrawal, String publicBaseUrl) {
        this.providers = Map.copyOf(providers);
        this.appleWithdrawal = appleWithdrawal;
        this.siteOrigin = originOf(publicBaseUrl);
    }

    /** 앱 애플 탈퇴. 애플 앱 로그인이 켜져 있을 때만 있다. */
    Optional<AppleAppWithdrawal> appleWithdrawal() {
        return appleWithdrawal;
    }

    /** CSRF 토큰을 인증 쿠키와 상관없이 요구할 앱 로그인 요청인지. 보안 설정이 쓴다. */
    public static boolean isAppLoginRequest(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && APP_LOGIN_PATH.matcher(request.getRequestURI()).matches();
    }

    /** 경로의 제공자 이름({@code apple}·{@code kakao})으로 켜진 제공자를 찾는다. */
    Optional<Provider> provider(String pathName) {
        return Optional.ofNullable(providers.get(pathName));
    }

    boolean fromSite(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        return origin == null || origin.equals(siteOrigin);
    }

    private static String originOf(String publicBaseUrl) {
        URI uri = URI.create(publicBaseUrl);
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
        if (uri.getPort() != -1) {
            origin += ":" + uri.getPort();
        }
        return origin;
    }

    record Provider(LoginProvider provider, AppIdTokenVerifier verifier) {
    }
}
