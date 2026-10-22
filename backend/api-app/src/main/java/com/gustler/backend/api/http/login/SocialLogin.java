package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.AuthCookies;
import com.gustler.backend.api.account.token.WithdrawalTickets;
import com.gustler.backend.api.favorite.application.DefaultFavoriteGroupService;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 켜진 제공자들의 로그인 흐름을 보안 필터 체인에 붙인다.
 *
 * <p>시작은 {@code /api/auth/{provider}/start}, 콜백은 {@code /api/auth/{provider}/callback} 이다.
 * 제공자 토큰은 저장하지 않고, 토큰 교환은 제한 시간을 두어 제공자가 응답하지 않아도 요청 스레드가 매달리지 않는다.
 * 켜진 제공자가 없으면 아무것도 붙이지 않는다. 애플 탈퇴 시작을 막는 검사는 애플이 켜져 있을 때만 붙인다. 꺼져 있으면
 * 애플 시작 경로는 다른 꺼진 제공자처럼 404 로 답한다.
 *
 * <p>고정 비밀 대신 요청마다 만든 {@code client_secret} 을 쓰는 제공자(애플)는 등록 ID 별 발급기로 받는다. 이런
 * 등록은 비밀 값 없이 {@code client_secret_post} 로 두고, 기본 변환기가 넣은 {@code client_secret} 자리를 발급기의
 * 값으로 바꾼다. 덧붙이면 같은 이름이 두 번 전송된다.
 */
public final class SocialLogin {
    static final String CALLBACK_PATTERN = "/api/auth/*/callback";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final List<ClientRegistration> registrations;
    private final Map<String, Supplier<String>> clientSecrets;
    private final SignInWithSocialAccount signIn;
    private final RefreshTokens refreshTokens;
    private final ObjectProvider<AccessTokens> accessTokens;
    private final ObjectProvider<AuthCookies> authCookies;
    private final DefaultFavoriteGroupService defaultGroups;
    private final ObjectProvider<AppleWithdrawal> appleWithdrawal;
    private final ObjectProvider<WithdrawalTickets> withdrawalTickets;

    public SocialLogin(
        List<ClientRegistration> registrations,
        Map<String, Supplier<String>> clientSecrets,
        SignInWithSocialAccount signIn,
        RefreshTokens refreshTokens,
        ObjectProvider<AccessTokens> accessTokens,
        ObjectProvider<AuthCookies> authCookies,
        DefaultFavoriteGroupService defaultGroups,
        ObjectProvider<AppleWithdrawal> appleWithdrawal,
        ObjectProvider<WithdrawalTickets> withdrawalTickets
    ) {
        this.registrations = registrations;
        this.clientSecrets = clientSecrets;
        this.signIn = signIn;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.authCookies = authCookies;
        this.defaultGroups = defaultGroups;
        this.appleWithdrawal = appleWithdrawal;
        this.withdrawalTickets = withdrawalTickets;
    }

    public void apply(HttpSecurity http) throws Exception {
        if (registrations.isEmpty()) {
            return;
        }
        InMemoryClientRegistrationRepository repository = new InMemoryClientRegistrationRepository(registrations);
        OAuth2AuthorizedClientRepository discardingTokens = new DiscardingAuthorizedClientRepository();
        SocialLoginFailureHandler failureHandler = new SocialLoginFailureHandler();
        Optional<AppleWithdrawal> withdrawal = Optional.ofNullable(appleWithdrawal.getIfAvailable());
        http.oauth2Login(login -> login
            .clientRegistrationRepository(repository)
            .authorizedClientRepository(discardingTokens)
            .loginPage(ReturnTo.HOME)
            .authorizationEndpoint(endpoint -> endpoint
                .authorizationRequestResolver(new LoginStartResolver(repository)))
            .redirectionEndpoint(endpoint -> endpoint.baseUri(CALLBACK_PATTERN))
            .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenClient()))
            .successHandler(new SocialLoginSuccessHandler(
                signIn, refreshTokens, accessTokens.getObject(), authCookies.getObject(), defaultGroups,
                failureHandler, withdrawal))
            .failureHandler(failureHandler));
        if (withdrawal.isPresent()) {
            http.addFilterBefore(
                new WithdrawStartGuard(withdrawalTickets.getObject()), OAuth2AuthorizationRequestRedirectFilter.class);
        }
    }

    private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient() {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(RestClient.builder()
            .requestFactory(requestFactory)
            .configureMessageConverters(converters -> converters.registerDefaults()
                .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build());
        client.setParametersConverter(this::generatedClientSecret);
        return client;
    }

    private MultiValueMap<String, String> generatedClientSecret(OAuth2AuthorizationCodeGrantRequest request) {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        Supplier<String> clientSecret = clientSecrets.get(request.getClientRegistration().getRegistrationId());
        if (clientSecret != null) {
            parameters.set("client_secret", clientSecret.get());
        }
        return parameters;
    }
}
