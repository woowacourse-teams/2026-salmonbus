package com.gustler.backend.api.http.token;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.BearerTokens;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

/**
 * 액세스 토큰으로 회원을 인증하는 필터를 보안 필터 체인에 붙인다.
 *
 * <p>리졸버는 {@code Authorization} 헤더만 본다. 쿠키의 토큰은 CSRF 검사가 끝난 뒤 {@link CookieAccessTokenFilter} 가
 * 헤더로 옮긴다. 리졸버가 쿠키까지 보면 Spring Security 가 "Bearer 토큰이 있는 요청"으로 보고 CSRF 검사를 면제해 버린다.
 *
 * <p>토큰을 읽는 경로는 로그인이 필요한 {@code /api/me} 아래뿐이다. 공개 조회와 {@code /api/auth} 아래 경로는 만료되거나
 * 깨진 토큰이 실려 와도 거절하지 않는다. 그렇지 않으면 오래된 쿠키 때문에 공개 API가 401을 내거나, 만료된 토큰으로는 갱신조차
 * 못 하게 된다. 로그인이 꺼져 있으면 아무것도 붙이지 않는다.
 */
public final class TokenAuthentication {
    private static final String MEMBER_ROLE = "ROLE_MEMBER";

    private final ObjectProvider<AccessTokens> accessTokens;

    public TokenAuthentication(ObjectProvider<AccessTokens> accessTokens) {
        this.accessTokens = accessTokens;
    }

    public void apply(
        HttpSecurity http,
        AuthenticationEntryPoint entryPoint,
        AccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        AccessTokens tokens = accessTokens.getIfAvailable();
        if (tokens == null) {
            return;
        }
        BearerTokenResolver resolver = request -> {
            if (!CookieAccessTokenFilter.isProtectedPath(request)) {
                return null;
            }
            return BearerTokens.header(request).orElse(null);
        };
        http.oauth2ResourceServer(resourceServer -> resourceServer
            .bearerTokenResolver(resolver)
            .authenticationEntryPoint(entryPoint)
            .accessDeniedHandler(accessDeniedHandler)
            .jwt(jwt -> jwt
                .decoder(tokens.decoder())
                .jwtAuthenticationConverter(source -> authenticationOf(source.getSubject()))));
        http.addFilterBefore(new CookieAccessTokenFilter(), BearerTokenAuthenticationFilter.class);
    }

    private static AbstractAuthenticationToken authenticationOf(String subject) {
        try {
            return UsernamePasswordAuthenticationToken.authenticated(
                new MemberPrincipal(UUID.fromString(subject)), null, List.of(new SimpleGrantedAuthority(MEMBER_ROLE)));
        } catch (IllegalArgumentException exception) {
            throw new InvalidBearerTokenException("회원 식별자가 올바르지 않다", exception);
        }
    }
}
