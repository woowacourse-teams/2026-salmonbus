package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.AuthCookies;
import com.gustler.backend.api.favorite.application.DefaultFavoriteGroupService;
import com.gustler.backend.api.monitoring.UserMonitoring;
import com.gustler.backend.api.monitoring.UserMonitoring.LoginResult;
import com.gustler.backend.api.monitoring.UserMonitoring.MemberKind;
import com.gustler.backend.member.api.IssuedRefreshToken;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SignedInMember;
import com.gustler.backend.member.api.SocialIdentity;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 제공자 인증을 마치면 회원으로 로그인시키고 처음 가려던 곳으로 돌려보낸다.
 *
 * <p>제공자 인증 결과(ID 토큰 클레임 포함)는 회원을 찾는 데만 쓰고 저장하지 않는다. 로그인 상태는 액세스 토큰과
 * 리프레시 토큰을 쿠키로 내려 주는 것으로 시작한다. 시작 때 만든 임시 세션은 쓸 곳이 없으므로 지운다.
 *
 * <p>회원 처리나 토큰 발급에 실패하면 쿠키를 내리지 않고 실패 경로로 돌려보낸다.
 *
 * <p>탈퇴를 위해 다시 인증한 콜백이면 로그인하지 않고 {@link AppleWithdrawal} 로 넘긴다. 탈퇴했으면 인증 쿠키를 지우고
 * {@code /?withdrawn=1} 로 보낸다. 다시 인증한 계정이 다르거나 애플 토큰을 취소하지 못했거나 처리에 실패했으면 인증 쿠키를
 * 그대로 두고 {@code withdraw=failed} 를 붙여 돌려보낸다.
 *
 * <p>리다이렉트 주소는 컨테이너가 절대 주소로 바꾸지 않도록 헤더에 직접 쓴다.
 */
final class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SocialLoginSuccessHandler.class);
    private static final String WITHDRAWN_PARAMETER = "withdrawn";
    private static final String WITHDRAW_PARAMETER = "withdraw";

    private final SignInWithSocialAccount signIn;
    private final RefreshTokens refreshTokens;
    private final AccessTokens accessTokens;
    private final AuthCookies authCookies;
    private final DefaultFavoriteGroupService defaultGroups;
    private final AuthenticationFailureHandler failureHandler;
    private final Optional<AppleWithdrawal> withdrawal;
    private final UserMonitoring monitoring;

    SocialLoginSuccessHandler(
        SignInWithSocialAccount signIn,
        RefreshTokens refreshTokens,
        AccessTokens accessTokens,
        AuthCookies authCookies,
        DefaultFavoriteGroupService defaultGroups,
        AuthenticationFailureHandler failureHandler,
        Optional<AppleWithdrawal> withdrawal,
        UserMonitoring monitoring
    ) {
        this.signIn = signIn;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.authCookies = authCookies;
        this.defaultGroups = defaultGroups;
        this.failureHandler = failureHandler;
        this.withdrawal = withdrawal;
        this.monitoring = monitoring;
    }

    @Override
    public void onAuthenticationSuccess(
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication authentication
    ) throws IOException, ServletException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        OidcUser user = (OidcUser) token.getPrincipal();
        String registrationId = token.getAuthorizedClientRegistrationId();
        LoginProvider provider = LoginProvider.valueOf(registrationId.toUpperCase(Locale.ROOT));
        Optional<UUID> withdrawing = LoginSessions.withdrawingMember(request.getSession());
        if (withdrawing.isPresent()) {
            completeWithdrawal(request, response, withdrawing.get(), new MemberIdentity(provider, user.getSubject()));
            return;
        }
        HttpHeaders cookies = new HttpHeaders();
        SignedInMember member;
        try {
            member = signIn.signIn(new SocialIdentity(provider, user.getSubject()));
            ensureDefaultGroup(member.publicId());
            String accessToken = accessTokens.issue(member.publicId());
            IssuedRefreshToken refreshToken = refreshTokens.issue(member.publicId());
            authCookies.write(cookies, accessToken, refreshToken);
        } catch (RuntimeException exception) {
            LOGGER.error("event=user_login status=FAILED provider={} phase=member_or_token cause={}",
                provider.name().toLowerCase(Locale.ROOT), exception.getClass().getSimpleName());
            SecurityContextHolder.clearContext();
            failureHandler.onAuthenticationFailure(
                request, response, new AuthenticationServiceException("회원 로그인을 처리하지 못했다", exception));
            return;
        }

        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession();
        String returnTo = LoginSessions.takeReturnTo(session);
        session.invalidate();
        redirect(response, cookies, returnTo);
        monitoring.login(provider, LoginResult.SUCCESS, member.registered() ? MemberKind.NEW : MemberKind.EXISTING);
    }

    private void ensureDefaultGroup(UUID memberPublicId) {
        try {
            defaultGroups.ensure(memberPublicId);
        } catch (RuntimeException exception) {
            LOGGER.warn("기본 즐겨찾기 그룹을 만들지 못했습니다. 다음 즐겨찾기 조회에서 다시 시도합니다.", exception);
        }
    }

    private void completeWithdrawal(
        HttpServletRequest request,
        HttpServletResponse response,
        UUID memberId,
        MemberIdentity reauthenticated
    ) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession();
        String returnTo = LoginSessions.takeReturnTo(session);
        session.invalidate();
        HttpHeaders cookies = new HttpHeaders();
        if (withdrawn(request, memberId, reauthenticated)) {
            authCookies.clear(cookies);
            redirect(response, cookies, withQuery(ReturnTo.HOME, WITHDRAWN_PARAMETER, "1"));
            return;
        }
        redirect(response, cookies, withQuery(returnTo, WITHDRAW_PARAMETER, "failed"));
    }

    private boolean withdrawn(HttpServletRequest request, UUID memberId, MemberIdentity reauthenticated) {
        if (withdrawal.isEmpty()) {
            return false;
        }
        try {
            return withdrawal.get().withdraw(
                memberId, reauthenticated, DiscardingAuthorizedClientRepository.providerAccessToken(request));
        } catch (RuntimeException exception) {
            LOGGER.error("탈퇴를 처리하지 못했다", exception);
            return false;
        }
    }

    private static String withQuery(String path, String name, String value) {
        return UriComponentsBuilder.fromUriString(path).replaceQueryParam(name, value).build().toString();
    }

    private static void redirect(HttpServletResponse response, HttpHeaders cookies, String location) {
        cookies.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, location);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }
}
