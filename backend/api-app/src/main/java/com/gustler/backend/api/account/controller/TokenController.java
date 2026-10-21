package com.gustler.backend.api.account.controller;

import com.gustler.backend.api.account.dto.RefreshRequest;
import com.gustler.backend.api.account.dto.TokenResponse;
import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.AuthCookies;
import com.gustler.backend.api.account.token.BearerTokens;
import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.member.api.RefreshResult;
import com.gustler.backend.member.api.RefreshTokens;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리프레시 토큰으로 로그인을 이어 가거나 끝낸다.
 *
 * <p>리프레시 토큰은 본문의 {@code refreshToken} 을 먼저 보고 없으면 쿠키를 본다. 쿠키로 보낸 요청에는 쿠키로, 본문으로 보낸
 * 요청에는 본문으로 새 토큰을 돌려준다. 갱신에 실패하면 어떤 이유든 401 {@code UNAUTHENTICATED} 이고, 쿠키로 보낸 요청에는
 * 인증 쿠키를 지운다. 실패 이유를 구분해 알리지 않는다.
 *
 * <p>{@code auth.enabled} 가 {@code true} 일 때만 등록한다.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class TokenController {
    private static final Logger LOGGER = LoggerFactory.getLogger(TokenController.class);

    private final RefreshTokens refreshTokens;
    private final MeterRegistry meters;
    private final AccessTokens accessTokens;
    private final AuthCookies authCookies;

    public TokenController(
        RefreshTokens refreshTokens,
        AccessTokens accessTokens,
        AuthCookies authCookies,
        MeterRegistry meters
    ) {
        this.refreshTokens = refreshTokens;
        this.meters = meters;
        this.accessTokens = accessTokens;
        this.authCookies = authCookies;
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
        @RequestBody(required = false) RefreshRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        Optional<String> fromBody = bodyToken(body);
        Optional<String> token = fromBody.or(() -> BearerTokens.refreshCookie(request));
        RefreshResult result = rotate(token);
        if (!(result instanceof RefreshResult.Rotated rotated)) {
            record(result);
            if (fromBody.isEmpty()) {
                clearCookies(response);
            }
            throw new UnauthenticatedException();
        }
        String accessToken = accessTokens.issue(rotated.publicId());
        if (fromBody.isPresent()) {
            return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TokenResponse(
                    accessToken, accessTokens.accessTtlSeconds(), rotated.next().token(), rotated.next().expiresAt()));
        }
        HttpHeaders headers = new HttpHeaders();
        authCookies.write(headers, accessToken, rotated.next());
        return ResponseEntity.noContent().headers(headers).cacheControl(CacheControl.noStore()).build();
    }

    /** 이 로그인 계통의 리프레시 토큰을 모두 폐기하고 인증 쿠키를 지운다. 토큰이 없어도 204 다. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        @RequestBody(required = false) RefreshRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        bodyToken(body).or(() -> BearerTokens.refreshCookie(request)).ifPresent(refreshTokens::revoke);
        clearCookies(response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * 갱신이 거절된 이유를 로그와 지표에 남긴다. 클라이언트에는 이유를 알리지 않으므로 운영자가 볼 수 있는 곳은 여기뿐이다.
     * 재사용({@code REUSED}) 탐지는 탈취 신호일 수 있어 경고로 남기고, 토큰 값은 남기지 않는다.
     */
    private void record(RefreshResult result) {
        RefreshResult.Reason reason = ((RefreshResult.Rejected) result).reason();
        meters.counter("auth.refresh.rejected", "reason", reason.name()).increment();
        if (reason == RefreshResult.Reason.REUSED) {
            LOGGER.warn("이전 세대 리프레시 토큰이 다시 제시되어 로그인 계통을 폐기했다");
        }
    }

    private RefreshResult rotate(Optional<String> token) {
        if (token.isEmpty()) {
            return new RefreshResult.Rejected(RefreshResult.Reason.UNKNOWN);
        }
        return refreshTokens.rotate(token.get());
    }

    private static Optional<String> bodyToken(RefreshRequest body) {
        if (body == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(body.refreshToken()).filter(token -> !token.isBlank());
    }

    private void clearCookies(HttpServletResponse response) {
        HttpHeaders headers = new HttpHeaders();
        authCookies.clear(headers);
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }
}
