package com.gustler.backend.api.http.login;

import com.gustler.backend.member.api.SignInWithSocialAccount;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * 켜진 제공자의 {@link ClientRegistration} 을 만들어 {@link SocialLogin} 으로 묶는다.
 *
 * <p>제공자는 {@code auth.enabled} 와 그 제공자의 {@code auth.<제공자>.enabled} 가 모두 {@code true} 일 때만 켜진다.
 * 로그인 전체를 끄는 스위치가 제공자 경로까지 끄지 못하면 운영 중에 로그인을 내릴 수 없다.
 *
 * <p>카카오는 {@code client_secret_post} 로 인증하고 {@code openid profile_nickname} 범위를 요청한다.
 * 비밀은 설정에서 받으며 코드와 저장소에 두지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginProperties.class)
public class SocialLoginConfiguration {
    private static final String KAKAO = "kakao";

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
            .scope("openid", "profile_nickname")
            .issuerUri(kakao.issuer())
            .authorizationUri(kakao.authorizationUri())
            .tokenUri(kakao.tokenUri())
            .jwkSetUri(kakao.jwkSetUri())
            .userNameAttributeName("sub")
            .build();
    }

    @Bean
    SocialLogin socialLogin(ObjectProvider<ClientRegistration> registrations, SignInWithSocialAccount signIn) {
        return new SocialLogin(registrations.orderedStream().toList(), signIn);
    }
}
