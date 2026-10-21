package com.gustler.backend.api.http.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * {@code GET /api/auth/{provider}/start} 에서 제공자 로그인 요청을 만든다.
 *
 * <p>Spring Security 기본 주소는 {@code /oauth2/authorization/{id}} 라 우리 주소 규칙과 달라 직접 맞춘다.
 * 켜지 않은 제공자는 요청을 가로채지 않고 넘겨, 없는 경로로 응답한다.
 *
 * <p>시작 요청에서 세션이 처음 만들어진다. 이때 돌아갈 경로를 검증해 세션에 두고 수명을 짧게 잡는다.
 * 이미 로그인한 세션이면 수명은 그대로 둔다.
 * 모든 요청에 PKCE(S256)를 건다. 기본값은 비밀을 가진 클라이언트에는 PKCE를 걸지 않는다.
 */
final class LoginStartResolver implements OAuth2AuthorizationRequestResolver {
    private static final Pattern START_PATH = Pattern.compile("/api/auth/([a-z]+)/start");

    private final ClientRegistrationRepository registrations;
    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    LoginStartResolver(ClientRegistrationRepository registrations) {
        this.registrations = registrations;
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations);
        this.delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        Matcher matcher = START_PATH.matcher(request.getRequestURI());
        if (!matcher.matches() || registrations.findByRegistrationId(matcher.group(1)) == null) {
            return null;
        }
        return resolve(request, matcher.group(1));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        HttpSession session = request.getSession();
        if (!LoginSessions.isSignedIn(session)) {
            LoginSessions.expireAfter(session, LoginSessions.BEFORE_LOGIN);
        }
        session.setAttribute(LoginSessions.RETURN_TO_ATTRIBUTE, ReturnTo.sanitize(request.getParameter("returnTo")));
        return delegate.resolve(request, clientRegistrationId);
    }
}
