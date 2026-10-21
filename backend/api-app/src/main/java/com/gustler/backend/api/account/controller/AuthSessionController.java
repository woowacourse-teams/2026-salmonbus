package com.gustler.backend.api.account.controller;

import com.gustler.backend.api.account.dto.AuthSessionResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 로그인 상태를 알려 주고 CSRF 토큰 쿠키를 발급한다.
 *
 * <p>상태를 바꾸는 요청을 보내기 전에 클라이언트가 이 경로를 호출해 {@code XSRF-TOKEN} 쿠키를 받아야 한다.
 * {@code auth.enabled} 가 {@code true} 일 때만 등록한다.
 */
@RestController
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class AuthSessionController {
    @GetMapping("/api/auth/session")
    public ResponseEntity<AuthSessionResponse> session(CsrfToken csrfToken) {
        csrfToken.getToken();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new AuthSessionResponse(false));
    }
}
