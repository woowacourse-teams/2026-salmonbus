package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.AuthCookies;
import com.gustler.backend.api.favorite.application.DefaultFavoriteGroupService;
import com.gustler.backend.member.api.IssuedRefreshToken;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SignedInMember;
import com.gustler.backend.member.api.SocialIdentity;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

/**
 * 제공자가 인증한 계정을 회원으로 로그인시키고 인증 쿠키를 만든다. 웹 콜백과 앱 로그인이 함께 쓴다.
 *
 * <p>회원은 {@code (제공자, sub)} 로 찾고 없으면 가입시킨다. 그래서 같은 계정이면 어느 경로로 들어와도 같은 회원이다.
 * 기본 즐겨찾기 그룹은 만들지 못해도 로그인을 막지 않는다. 다음 즐겨찾기 조회가 다시 만든다. 회원 처리나 토큰 발급이
 * 실패하면 예외를 그대로 던지고 쿠키를 만들지 않는다. 응답과 지표는 호출자가 정한다.
 */
final class LoginCompletion {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoginCompletion.class);

    private final SignInWithSocialAccount signIn;
    private final RefreshTokens refreshTokens;
    private final AccessTokens accessTokens;
    private final AuthCookies authCookies;
    private final DefaultFavoriteGroupService defaultGroups;

    LoginCompletion(
        SignInWithSocialAccount signIn,
        RefreshTokens refreshTokens,
        AccessTokens accessTokens,
        AuthCookies authCookies,
        DefaultFavoriteGroupService defaultGroups
    ) {
        this.signIn = signIn;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.authCookies = authCookies;
        this.defaultGroups = defaultGroups;
    }

    Completed complete(LoginProvider provider, String subject) {
        SignedInMember member = signIn.signIn(new SocialIdentity(provider, subject));
        ensureDefaultGroup(member.publicId());
        String accessToken = accessTokens.issue(member.publicId());
        IssuedRefreshToken refreshToken = refreshTokens.issue(member.publicId());
        HttpHeaders cookies = new HttpHeaders();
        authCookies.write(cookies, accessToken, refreshToken);
        return new Completed(cookies, member.registered());
    }

    private void ensureDefaultGroup(UUID memberPublicId) {
        try {
            defaultGroups.ensure(memberPublicId);
        } catch (RuntimeException exception) {
            LOGGER.warn("기본 즐겨찾기 그룹을 만들지 못했습니다. 다음 즐겨찾기 조회에서 다시 시도합니다.", exception);
        }
    }

    /** 로그인 결과. {@code cookies} 에는 인증 쿠키 두 개의 {@code Set-Cookie} 가 들어 있다. */
    record Completed(HttpHeaders cookies, boolean registered) {
    }
}
