package com.gustler.backend.api.http;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * 공개 조회 API는 그대로 열어 두고 회원 경로만 로그인을 요구한다.
 *
 * <p>CSRF 토큰은 필요한 요청에서만 만든다. 모든 요청에서 토큰을 만들면 캐시되는 공개 조회 응답에도
 * {@code Set-Cookie} 가 붙는다. 클라이언트는 {@code GET /api/auth/session} 으로 토큰 쿠키를 받는다.
 *
 * <p>요청 캐시를 끈다. 켜 두면 로그인이 필요한 요청을 거절할 때 그 요청을 저장하려고 세션을 만든다.
 */
@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {
    private static final String LOGOUT_PATH = "/api/auth/logout";

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        SecurityErrorResponder errors = new SecurityErrorResponder(objectMapper);
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
        http
            .authorizeHttpRequests(requests -> requests
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                .requestMatchers("/api/me", "/api/me/**").authenticated()
                .anyRequest().permitAll())
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokens)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(logout -> logout
                .logoutUrl(LOGOUT_PATH)
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(errors)
                .accessDeniedHandler(errors));
        return http.build();
    }
}
