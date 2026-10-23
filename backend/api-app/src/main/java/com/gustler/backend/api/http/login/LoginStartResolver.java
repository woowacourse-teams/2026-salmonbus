package com.gustler.backend.api.http.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
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
 * <p>시작 요청에서 임시 세션이 만들어진다. 이때 돌아갈 경로를 검증해 세션에 둔다. 로그인 상태는 세션이 아니라 토큰이 가지므로
 * 이미 로그인한 사용자가 다시 시작해도 그 로그인은 영향을 받지 않는다.
 * 애플을 뺀 모든 요청에 PKCE(S256)를 건다. 기본값은 비밀을 가진 클라이언트에는 PKCE를 걸지 않는다. 애플은 PKCE 를
 * 지원하지 않으므로 걸지 않고, 대신 {@code response_mode=query} 를 명시해 콜백이 쿼리로 돌아오게 한다. 폼 전송
 * ({@code form_post}) 으로 돌아오면 SameSite=Lax 세션 쿠키가 실리지 않아 로그인 전 세션을 잃는다.
 */
final class LoginStartResolver implements OAuth2AuthorizationRequestResolver {
    private static final Pattern START_PATH = Pattern.compile("/api/auth/([a-z]+)/start");
    private static final String RESPONSE_MODE = "response_mode";

    private final ClientRegistrationRepository registrations;
    private final DefaultOAuth2AuthorizationRequestResolver withPkce;
    private final DefaultOAuth2AuthorizationRequestResolver withoutPkce;

    LoginStartResolver(ClientRegistrationRepository registrations) {
        this.registrations = registrations;
        this.withPkce = new DefaultOAuth2AuthorizationRequestResolver(registrations);
        this.withPkce.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        this.withoutPkce = new DefaultOAuth2AuthorizationRequestResolver(registrations);
        this.withoutPkce.setAuthorizationRequestCustomizer(builder -> builder
            .additionalParameters(parameters -> parameters.put(RESPONSE_MODE, "query")));
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
        session.setAttribute(LoginSessions.RETURN_TO_ATTRIBUTE, ReturnTo.sanitize(request.getParameter("returnTo")));
        rememberWithdrawal(request, session);
        if (SocialLoginConfiguration.APPLE.equals(clientRegistrationId)) {
            return withoutPkce.resolve(request, clientRegistrationId);
        }
        return withPkce.resolve(request, clientRegistrationId);
    }

    /**
     * 탈퇴 시작이면 탈퇴할 회원을 임시 세션에 두고, 아니면 지운다. 같은 브라우저가 10분 안에 탈퇴를 시작했다가 그만두고 일반
     * 로그인을 시작하면, 지우지 않은 탈퇴 표시 때문에 로그인이 탈퇴로 처리될 수 있다.
     */
    private static void rememberWithdrawal(HttpServletRequest request, HttpSession session) {
        if (request.getAttribute(WithdrawStartGuard.MEMBER_ATTRIBUTE) instanceof UUID member) {
            session.setAttribute(LoginSessions.WITHDRAWING_MEMBER_ATTRIBUTE, member.toString());
            return;
        }
        session.removeAttribute(LoginSessions.WITHDRAWING_MEMBER_ATTRIBUTE);
    }
}
