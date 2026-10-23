package com.gustler.backend.api.http.login;

import com.gustler.backend.api.monitoring.UserMonitoring;
import com.gustler.backend.api.monitoring.UserMonitoring.LoginResult;
import com.gustler.backend.api.monitoring.UserMonitoring.MemberKind;
import com.gustler.backend.member.api.LoginProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 로그인에 실패하면 돌아갈 경로에 {@code login=cancelled} 또는 {@code login=failed} 를 붙여 돌려보낸다. 탈퇴를 위한
 * 재인증이었으면 {@code login} 대신 {@code withdraw} 를 붙인다.
 *
 * <p>사용자가 제공자 화면에서 취소한 경우만 {@code cancelled} 이고 나머지는 모두 {@code failed} 다. 취소는 카카오가
 * {@code access_denied}, 애플이 {@code user_cancelled_authorize} 로 알린다.
 * 구분하지 않은 실패 원인은 로그에 남기지 않는다. {@code code}·{@code state}·토큰이 예외 메시지에 섞일 수 있다.
 * 로그인 전 세션은 쓸 곳이 없으므로 지운다. 로그인 상태는 이 세션에 없으므로 이미 로그인한 사용자의
 * 로그인은 그대로다.
 */
final class SocialLoginFailureHandler implements AuthenticationFailureHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SocialLoginFailureHandler.class);
    private static final String LOGIN_PARAMETER = "login";
    private static final String WITHDRAW_PARAMETER = "withdraw";
    private static final Set<String> CANCELLATION_CODES = Set.of(
        OAuth2ErrorCodes.ACCESS_DENIED, "user_cancelled_authorize");

    private final UserMonitoring monitoring;

    SocialLoginFailureHandler(UserMonitoring monitoring) {
        this.monitoring = monitoring;
    }

    @Override
    public void onAuthenticationFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException exception
    ) {
        String reason = reasonOf(exception);
        HttpSession session = request.getSession(false);
        String returnTo = ReturnTo.HOME;
        String parameter = LOGIN_PARAMETER;
        boolean withdrawal = false;
        if (session != null) {
            returnTo = LoginSessions.takeReturnTo(session);
            if (LoginSessions.withdrawingMember(session).isPresent()) {
                parameter = WITHDRAW_PARAMETER;
                withdrawal = true;
            }
            session.invalidate();
        }
        Optional<LoginProvider> provider = providerOf(request);
        if (!withdrawal) {
            LoginResult result = reason.equals("cancelled") ? LoginResult.CANCELLED : LoginResult.FAILED;
            monitoring.login(provider, result, MemberKind.UNKNOWN);
        }
        LOGGER.warn("event=user_login status=FAILED provider={} reason={} cause={}",
            provider.map(value -> value.name().toLowerCase(Locale.ROOT)).orElse("unknown"),
            reason, exception.getClass().getSimpleName());
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION,
            UriComponentsBuilder.fromUriString(returnTo).replaceQueryParam(parameter, reason).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }

    private static String reasonOf(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2
            && CANCELLATION_CODES.contains(oauth2.getError().getErrorCode())) {
            return "cancelled";
        }
        return "failed";
    }

    private static Optional<LoginProvider> providerOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (LoginProvider provider : LoginProvider.values()) {
            String name = provider.name().toLowerCase(Locale.ROOT);
            if (uri.equals("/api/auth/" + name + "/callback")) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
