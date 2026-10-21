package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SignedInMember;
import com.gustler.backend.member.api.SocialIdentity;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * 제공자 인증을 마치면 회원으로 로그인시키고 처음 가려던 곳으로 돌려보낸다.
 *
 * <p>제공자 인증 결과(ID 토큰 클레임 포함)를 {@link MemberPrincipal} 로 바꿔 세션에 저장한다. 그대로 두면
 * 제공자 식별자와 닉네임이 세션 표에 남는다. 세션 ID는 인증 직후 Spring Security 가 이미 교체했다.
 *
 * <p>회원 처리에 실패하면 제공자 인증 결과가 이미 세션에 저장돼 있으므로, 그것을 지우고 실패 경로로 돌려보낸다.
 * 그대로 두면 제공자 인증만 마친 세션이 로그인한 것으로 통한다.
 *
 * <p>리다이렉트 주소는 컨테이너가 절대 주소로 바꾸지 않도록 헤더에 직접 쓴다.
 */
final class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SocialLoginSuccessHandler.class);
    private static final String NICKNAME_CLAIM = "nickname";
    private static final String MEMBER_ROLE = "ROLE_MEMBER";

    private final SignInWithSocialAccount signIn;
    private final AuthenticationFailureHandler failureHandler;
    private final HttpSessionSecurityContextRepository securityContexts = new HttpSessionSecurityContextRepository();

    SocialLoginSuccessHandler(SignInWithSocialAccount signIn, AuthenticationFailureHandler failureHandler) {
        this.signIn = signIn;
        this.failureHandler = failureHandler;
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
        String nickname = user.getClaimAsString(NICKNAME_CLAIM);
        SignedInMember member;
        try {
            member = signIn.signIn(new SocialIdentity(provider, user.getSubject(), nickname));
        } catch (RuntimeException exception) {
            rejectLogin(request, response, exception);
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            new MemberPrincipal(member.publicId()), null, List.of(new SimpleGrantedAuthority(MEMBER_ROLE))));
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);

        HttpSession session = request.getSession();
        LoginSessions.expireAfter(session, LoginSessions.AFTER_LOGIN);
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, LoginSessions.takeReturnTo(session));
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }

    private void rejectLogin(
        HttpServletRequest request,
        HttpServletResponse response,
        RuntimeException cause
    ) throws IOException, ServletException {
        LOGGER.error("회원 로그인을 처리하지 못했다", cause);
        SecurityContextHolder.clearContext();
        request.getSession().removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        failureHandler.onAuthenticationFailure(
            request, response, new AuthenticationServiceException("회원 로그인을 처리하지 못했다", cause));
    }
}
