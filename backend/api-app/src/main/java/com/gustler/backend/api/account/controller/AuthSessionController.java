package com.gustler.backend.api.account.controller;

import com.gustler.backend.api.account.dto.AuthSessionResponse;
import com.gustler.backend.api.account.dto.MemberResponse;
import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.BearerTokens;
import com.gustler.backend.member.api.ViewMember;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 로그인 상태를 알려 주고 CSRF 토큰 쿠키를 발급한다.
 *
 * <p>상태를 바꾸는 요청을 보내기 전에 클라이언트가 이 경로를 호출해 {@code __Host-XSRF-TOKEN} 쿠키를 받아야 한다.
 * 만료되거나 깨진 토큰이 와도 401 을 내지 않고 로그인하지 않은 것으로 답한다. 토큰의 회원이 이미 탈퇴했어도 같다.
 * {@code auth.enabled} 가 {@code true} 일 때만 등록한다.
 */
@RestController
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class AuthSessionController {
    private final ViewMember viewMember;
    private final AccessTokens accessTokens;

    public AuthSessionController(ViewMember viewMember, AccessTokens accessTokens) {
        this.viewMember = viewMember;
        this.accessTokens = accessTokens;
    }

    @GetMapping("/api/auth/session")
    public ResponseEntity<AuthSessionResponse> session(CsrfToken csrfToken, HttpServletRequest request) {
        csrfToken.getToken();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(responseOf(request));
    }

    private AuthSessionResponse responseOf(HttpServletRequest request) {
        Optional<UUID> memberId = BearerTokens.access(request).flatMap(accessTokens::verify);
        Optional<MemberResponse> member = memberId.flatMap(viewMember::view).map(MemberResponse::from);
        if (member.isPresent()) {
            return AuthSessionResponse.of(member.get());
        }
        return AuthSessionResponse.anonymous(BearerTokens.refreshCookie(request).isPresent());
    }
}
