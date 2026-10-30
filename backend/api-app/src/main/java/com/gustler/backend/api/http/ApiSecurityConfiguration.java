package com.gustler.backend.api.http;

import com.gustler.backend.api.account.token.BearerTokens;
import com.gustler.backend.api.http.login.AppLogin;
import com.gustler.backend.api.http.login.SocialLogin;
import com.gustler.backend.api.http.token.TokenAuthentication;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * 공개 조회 API는 그대로 열어 두고 회원 경로만 로그인을 요구한다.
 *
 * <p>CSRF 토큰은 필요한 요청에서만 만든다. 모든 요청에서 토큰을 만들면 캐시되는 공개 조회 응답에도
 * {@code Set-Cookie} 가 붙는다. 클라이언트는 {@code GET /api/auth/session} 으로 토큰 쿠키를 받는다.
 *
 * <p>CSRF 쿠키 이름은 {@code __Host-} 접두사를 붙여 브라우저가 호스트 전용({@code Path=/}, {@code Domain} 없음, {@code Secure})
 * 으로만 받게 한다. 형제 도메인(dev 등)의 스크립트가 {@code Domain} 을 지정해 같은 이름의 쿠키를 심고 같은 값을 되돌려
 * 보내는 우회를 막는다. 토큰 값은 헤더로만 받는다. 폼 파라미터로 받으면 헤더 없이 일반 폼 전송으로 도용할 수 있다.
 *
 * <p>CSRF 토큰은 쿠키로 인증하는 변경 요청에만 요구한다. 인증 쿠키가 없거나 {@code Authorization} 헤더로 인증하는 요청은
 * 다른 사이트가 도용할 수 있는 권한이 없다. 예외는 앱 로그인 요청({@link AppLogin#isAppLoginRequest})이다. 로그인 전이라
 * 쿠키가 없지만, 다른 사이트가 공격자 계정의 토큰으로 사용자를 로그인시키지 못하게 늘 요구한다.
 *
 * <p>세션 고정 보호는 끈다. 세션은 로그인 중에만 쓰는 임시 값이고 로그인하면 지우므로, 로그인 상태를 담은 세션이 없어
 * 고정 공격의 대상이 없다. 켜 두면 세션 ID 교체가 저장되기 전에 세션을 지우는 탓에 행이 만료까지 남는다.
 *
 * <p>요청 캐시를 끈다. 켜 두면 로그인이 필요한 요청을 거절할 때 그 요청을 저장하려고 세션을 만든다.
 *
 * <p>로그인이 꺼져 있으면({@code auth.enabled} 가 {@code true} 가 아니면) 회원 경로에 로그인을 요구하지 않고 CSRF 검사도
 * 하지 않는다. 로그인 경로는 컨트롤러가 등록되지 않으므로, 남은 인증 쿠키가 실려 와도 모두 404 로 답한다. 스위치는
 * 컨트롤러의 등록 조건({@code havingValue = "true"})과 같은 기준으로 읽는다. {@code boolean} 으로 받으면 {@code 1}·{@code on}
 * 같은 값을 켜짐으로 읽어 두 기준이 갈린다.
 */
@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {
    static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
        HttpSecurity http,
        ObjectMapper objectMapper,
        SocialLogin socialLogin,
        TokenAuthentication tokenAuthentication,
        @Value("${auth.enabled:false}") String loginSwitch
    ) throws Exception {
        boolean loginEnabled = "true".equalsIgnoreCase(loginSwitch);
        SecurityErrorResponder errors = new SecurityErrorResponder(objectMapper);
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieName(CSRF_COOKIE);
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax").path("/"));
        http
            .authorizeHttpRequests(requests -> {
                requests.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll();
                if (loginEnabled) {
                    requests.requestMatchers("/api/me", "/api/me/**").authenticated();
                }
                requests.anyRequest().permitAll();
            })
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokens)
                .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler())
                .ignoringRequestMatchers(request -> !loginEnabled
                    || (!BearerTokens.usesAmbientCredentials(request) && !AppLogin.isAppLoginRequest(request))))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.none()))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(errors)
                .accessDeniedHandler(errors));
        tokenAuthentication.apply(http, errors, errors);
        socialLogin.apply(http);
        return http.build();
    }
}
