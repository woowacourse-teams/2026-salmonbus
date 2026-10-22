package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.AuthCookies;
import com.gustler.backend.api.account.token.WithdrawalTickets;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.WithdrawMember;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Clock;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.RestClient;

/**
 * 켜진 제공자의 {@link ClientRegistration} 을 만들어 {@link SocialLogin} 으로 묶는다.
 *
 * <p>제공자는 {@code auth.enabled} 와 그 제공자의 {@code auth.<제공자>.enabled} 가 모두 {@code true} 일 때만 켜진다.
 * 로그인 전체를 끄는 스위치가 제공자 경로까지 끄지 못하면 운영 중에 로그인을 내릴 수 없다.
 *
 * <p>카카오는 {@code client_secret_post} 로 인증하고 {@code openid} 범위만 요청한다. 가입 닉네임은 회원 모듈이 만들므로
 * 카카오 프로필 닉네임은 받지 않는다. 비밀은 설정에서 받으며 코드와 저장소에 두지 않는다.
 *
 * <p>애플은 {@code client_secret_post} 로 인증하지만 비밀이 고정값이 아니라 요청마다 만드는 JWT 라
 * ({@link AppleClientAssertion}) 등록에는 비밀을 두지 않는다. Spring Security 는 {@code requireProofKey} 기본값이 켜져 있어
 * 인가 요청에 PKCE 를 걸므로 애플 등록에서는 명시적으로 끈다. 요청 범위는 {@code auth.apple.scopes} 이며 기본은
 * {@code openid} 다. Spring Security 는 {@code openid} 가 있어야 ID 토큰 흐름으로 처리한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginProperties.class)
public class SocialLoginConfiguration {
    static final String APPLE = "apple";

    private static final String KAKAO = "kakao";
    private static final Duration PROVIDER_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration PROVIDER_READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    @ConditionalOnProperty(prefix = "auth", name = {"enabled", "kakao.enabled"}, havingValue = "true")
    ClientRegistration kakaoClientRegistration(LoginProperties properties) {
        LoginProperties.Kakao kakao = properties.kakao();
        return ClientRegistration.withRegistrationId(KAKAO)
            .clientName("Kakao")
            .clientId(kakao.clientId())
            .clientSecret(kakao.clientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(properties.publicBaseUrl() + "/api/auth/" + KAKAO + "/callback")
            .scope("openid")
            .issuerUri(kakao.issuer())
            .authorizationUri(kakao.authorizationUri())
            .tokenUri(kakao.tokenUri())
            .jwkSetUri(kakao.jwkSetUri())
            .userNameAttributeName("sub")
            .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "auth", name = {"enabled", "apple.enabled"}, havingValue = "true")
    AppleClientAssertion appleClientAssertion(LoginProperties properties, Clock clock) {
        return AppleClientAssertion.load(properties.apple(), clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "auth", name = {"enabled", "apple.enabled"}, havingValue = "true")
    ClientRegistration appleClientRegistration(LoginProperties properties) {
        LoginProperties.Apple apple = properties.apple().requireComplete();
        return ClientRegistration.withRegistrationId(APPLE)
            .clientName("Apple")
            .clientId(apple.clientId())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(properties.publicBaseUrl() + "/api/auth/" + APPLE + "/callback")
            .scope(apple.scopes())
            .issuerUri(apple.issuer())
            .authorizationUri(apple.authorizationUri())
            .tokenUri(apple.tokenUri())
            .jwkSetUri(apple.jwkSetUri())
            .userNameAttributeName("sub")
            .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(false).build())
            .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "auth", name = {"enabled", "apple.enabled"}, havingValue = "true")
    AppleWithdrawal appleWithdrawal(
        LoginProperties properties,
        AppleClientAssertion clientSecret,
        WithdrawMember withdrawMember,
        MeterRegistry meters
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(PROVIDER_CONNECT_TIMEOUT)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(PROVIDER_READ_TIMEOUT);
        AppleRevokeClient revokeClient = new AppleRevokeClient(
            RestClient.builder().requestFactory(requestFactory).build(),
            properties.apple().clientId(), properties.apple().revokeUri(), clientSecret);
        return new AppleWithdrawal(withdrawMember, revokeClient, meters);
    }

    @Bean
    SocialLogin socialLogin(
        ObjectProvider<ClientRegistration> registrations,
        ObjectProvider<AppleClientAssertion> appleClientAssertion,
        SignInWithSocialAccount signIn,
        RefreshTokens refreshTokens,
        ObjectProvider<AccessTokens> accessTokens,
        ObjectProvider<AuthCookies> authCookies,
        ObjectProvider<AppleWithdrawal> appleWithdrawal,
        ObjectProvider<WithdrawalTickets> withdrawalTickets
    ) {
        Map<String, Supplier<String>> clientSecrets = appleClientAssertion.stream()
            .collect(Collectors.toMap(secret -> APPLE, secret -> secret::issue));
        return new SocialLogin(
            registrations.orderedStream().toList(), clientSecrets, signIn, refreshTokens, accessTokens, authCookies,
            appleWithdrawal, withdrawalTickets);
    }
}
