package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.AuthCookies;
import com.gustler.backend.api.favorite.application.DefaultFavoriteGroupService;
import com.gustler.backend.api.http.login.AppIdTokenVerifier.NonceRule;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.WithdrawMember;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * 앱 네이티브 로그인 도구를 만든다. 로그인이 켜졌을 때만 등록한다.
 *
 * <p>제공자는 그 제공자의 웹 로그인 스위치({@code auth.<제공자>.enabled})가 켜지고 앱용 대상 값이 있을 때만 앱 로그인에
 * 들어간다. 스위치는 웹 로그인과 같은 기준({@code "true"}, 대소문자 무시)으로 읽는다. 발급자와 공개키 주소는 웹 로그인
 * 설정을 그대로 쓴다.
 *
 * <p>애플 앱 로그인이 켜지면 앱 애플 탈퇴도 함께 켠다. 코드 교환과 토큰 취소는 웹과 같은 키로 만든 앱 번들 ID 용
 * {@code client_secret} 을 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@EnableConfigurationProperties(AppLoginProperties.class)
public class AppLoginConfiguration {
    @Bean
    AppLogin appLogin(
        LoginProperties login,
        AppLoginProperties app,
        Environment environment,
        Clock clock,
        ObjectProvider<AppleClientAssertion> appleClientAssertion,
        WithdrawMember withdrawMember,
        MeterRegistry meters,
        ObjectMapper objectMapper
    ) {
        Map<String, AppLogin.Provider> providers = new HashMap<>();
        Optional<AppleAppWithdrawal> appleWithdrawal = Optional.empty();
        if (enabled(environment, "auth.apple.enabled") && present(app.appleClientId())) {
            LoginProperties.Apple apple = login.apple();
            AppIdTokenVerifier verifier = AppIdTokenVerifier.create(
                apple.issuer(), app.appleClientId(), apple.jwkSetUri(), NonceRule.SHA256_HEX, clock);
            providers.put("apple", new AppLogin.Provider(LoginProvider.APPLE, verifier));
            AppleClientAssertion appSecret = appleClientAssertion.getObject().forClient(app.appleClientId());
            appleWithdrawal = Optional.of(new AppleAppWithdrawal(
                new AppleAppTokenClient(SocialLoginConfiguration.providerRestClient(), app.appleClientId(),
                    apple.tokenUri(), appSecret, objectMapper),
                verifier,
                new AppleWithdrawal(withdrawMember, new AppleRevokeClient(SocialLoginConfiguration.providerRestClient(),
                    app.appleClientId(), apple.revokeUri(), appSecret), meters),
                meters));
        }
        if (enabled(environment, "auth.kakao.enabled") && present(app.kakaoAppKey())) {
            LoginProperties.Kakao kakao = login.kakao();
            providers.put("kakao", new AppLogin.Provider(LoginProvider.KAKAO, AppIdTokenVerifier.create(
                kakao.issuer(), app.kakaoAppKey(), kakao.jwkSetUri(), NonceRule.EXACT, clock)));
        }
        return new AppLogin(providers, appleWithdrawal, login.publicBaseUrl());
    }

    @Bean
    AppLoginNonces appLoginNonces() {
        return new AppLoginNonces();
    }

    @Bean
    LoginCompletion loginCompletion(
        SignInWithSocialAccount signIn,
        RefreshTokens refreshTokens,
        AccessTokens accessTokens,
        AuthCookies authCookies,
        DefaultFavoriteGroupService defaultGroups
    ) {
        return new LoginCompletion(signIn, refreshTokens, accessTokens, authCookies, defaultGroups);
    }

    private static boolean enabled(Environment environment, String name) {
        return "true".equalsIgnoreCase(environment.getProperty(name));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
