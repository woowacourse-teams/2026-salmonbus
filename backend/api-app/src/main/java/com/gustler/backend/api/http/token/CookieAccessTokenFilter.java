package com.gustler.backend.api.http.token;

import com.gustler.backend.api.account.token.BearerTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 쿠키의 액세스 토큰을 {@code Authorization} 헤더로 옮긴다.
 *
 * <p>CSRF 필터 뒤, Bearer 토큰 필터 앞에 둔다. 헤더 인증 요청은 CSRF 검사를 면제받으므로, 쿠키 요청을 CSRF 필터 앞에서
 * 헤더로 바꾸면 쿠키로 인증한 요청도 면제받는다. 이 필터는 로그인이 필요한 경로에서 헤더가 없을 때만 동작한다.
 */
final class CookieAccessTokenFilter extends OncePerRequestFilter {
    static boolean isProtectedPath(HttpServletRequest request) {
        return request.getRequestURI().equals("/api/me") || request.getRequestURI().startsWith("/api/me/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isProtectedPath(request) || BearerTokens.header(request).isPresent()) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<String> cookie = BearerTokens.access(request);
        if (cookie.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        filterChain.doFilter(new BearerHeaderRequest(request, "Bearer " + cookie.get()), response);
    }

    private static final class BearerHeaderRequest extends HttpServletRequestWrapper {
        private final String authorization;

        BearerHeaderRequest(HttpServletRequest request, String authorization) {
            super(request);
            this.authorization = authorization;
        }

        @Override
        public String getHeader(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return authorization;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(authorization));
            }
            return super.getHeaders(name);
        }
    }
}
