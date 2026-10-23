package com.gustler.backend.api.account.token;

import com.gustler.backend.member.api.IssuedRefreshToken;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * 웹과 웹뷰가 쓰는 인증 쿠키를 만든다.
 *
 * <p>둘 다 {@code HttpOnly; Secure; SameSite=Lax; Path=/} 이고 이름에 {@code __Host-} 접두사를 붙여 호스트 전용으로만
 * 받게 한다. dev 같은 형제 도메인의 스크립트가 {@code Domain} 을 지정해 같은 이름의 쿠키를 심으면, 로그아웃한 사용자가
 * 공격자의 계정으로 로그인되는 혼동이 생길 수 있어서다. 리프레시 토큰 쿠키가 {@code /} 경로로 가더라도 CloudFront 는
 * 로그인 경로({@code /api/auth}, {@code /api/me}) 밖으로는 쿠키를 전달하지 않는다.
 */
public final class AuthCookies {
    public static final String ACCESS = "__Host-sb_at";
    public static final String REFRESH = "__Host-sb_rt";

    private final Clock clock;
    private final TokenProperties properties;

    public AuthCookies(TokenProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void write(HttpHeaders headers, String accessToken, IssuedRefreshToken refreshToken) {
        headers.add(HttpHeaders.SET_COOKIE, base(ACCESS, accessToken, "/")
            .maxAge(properties.accessTtl()).build().toString());
        headers.add(HttpHeaders.SET_COOKIE, base(REFRESH, refreshToken.token(), "/")
            .maxAge(Duration.between(clock.instant(), refreshToken.expiresAt().toInstant())).build().toString());
    }

    public void clear(HttpHeaders headers) {
        headers.add(HttpHeaders.SET_COOKIE, base(ACCESS, "", "/").maxAge(Duration.ZERO).build().toString());
        headers.add(HttpHeaders.SET_COOKIE, base(REFRESH, "", "/").maxAge(Duration.ZERO).build().toString());
    }

    private static ResponseCookie.ResponseCookieBuilder base(String name, String value, String path) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(true).sameSite("Lax").path(path);
    }
}
