package com.gustler.backend.api.http.login;

import com.gustler.backend.member.api.SignInWithSocialAccount;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * 켜진 제공자들의 로그인 흐름을 보안 필터 체인에 붙인다.
 *
 * <p>시작은 {@code /api/auth/{provider}/start}, 콜백은 {@code /api/auth/{provider}/callback} 이다.
 * 제공자 토큰은 저장하지 않고, 토큰 교환은 제한 시간을 두어 제공자가 응답하지 않아도 요청 스레드가 매달리지 않는다.
 * 켜진 제공자가 없으면 아무것도 붙이지 않는다.
 */
public final class SocialLogin {
    static final String CALLBACK_PATTERN = "/api/auth/*/callback";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final List<ClientRegistration> registrations;
    private final SignInWithSocialAccount signIn;

    public SocialLogin(List<ClientRegistration> registrations, SignInWithSocialAccount signIn) {
        this.registrations = registrations;
        this.signIn = signIn;
    }

    public void apply(HttpSecurity http) throws Exception {
        if (registrations.isEmpty()) {
            return;
        }
        InMemoryClientRegistrationRepository repository = new InMemoryClientRegistrationRepository(registrations);
        OAuth2AuthorizedClientRepository discardingTokens = new DiscardingAuthorizedClientRepository();
        SocialLoginFailureHandler failureHandler = new SocialLoginFailureHandler();
        http.oauth2Login(login -> login
            .clientRegistrationRepository(repository)
            .authorizedClientRepository(discardingTokens)
            .loginPage(ReturnTo.HOME)
            .authorizationEndpoint(endpoint -> endpoint
                .authorizationRequestResolver(new LoginStartResolver(repository)))
            .redirectionEndpoint(endpoint -> endpoint.baseUri(CALLBACK_PATTERN))
            .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenClient()))
            .successHandler(new SocialLoginSuccessHandler(signIn, failureHandler))
            .failureHandler(failureHandler));
    }

    private static OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient() {
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
        return client;
    }
}
