package com.gustler.backend.api.account.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.account.dto.AuthSessionResponse;
import com.gustler.backend.api.account.dto.MemberResponse;
import com.gustler.backend.member.api.ViewMember;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 로그인 상태를 알려 주고 CSRF 토큰 쿠키를 발급한다.
 *
 * <p>상태를 바꾸는 요청을 보내기 전에 클라이언트가 이 경로를 호출해 {@code XSRF-TOKEN} 쿠키를 받아야 한다.
 * 세션에 남은 회원이 이미 탈퇴했으면 로그인하지 않은 것으로 답한다.
 * {@code auth.enabled} 가 {@code true} 일 때만 등록한다.
 */
@RestController
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class AuthSessionController {
    private final ViewMember viewMember;

    public AuthSessionController(ViewMember viewMember) {
        this.viewMember = viewMember;
    }

    @GetMapping("/api/auth/session")
    public ResponseEntity<AuthSessionResponse> session(
        CsrfToken csrfToken,
        @AuthenticationPrincipal MemberPrincipal principal
    ) {
        csrfToken.getToken();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(responseOf(principal));
    }

    private AuthSessionResponse responseOf(MemberPrincipal principal) {
        if (principal == null) {
            return AuthSessionResponse.anonymous();
        }
        Optional<MemberResponse> member = viewMember.view(principal.publicId()).map(MemberResponse::from);
        return member.map(AuthSessionResponse::of).orElseGet(AuthSessionResponse::anonymous);
    }
}
