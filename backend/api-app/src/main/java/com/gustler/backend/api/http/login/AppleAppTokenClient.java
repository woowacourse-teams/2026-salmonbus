package com.gustler.backend.api.http.login;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 앱의 Sign in with Apple 이 준 인가 코드를 앱 {@code client_id}(번들 ID)로 교환한다. 앱 탈퇴에서만 쓴다.
 *
 * <p>네이티브 앱 코드는 리다이렉트 주소 없이 받았으므로 {@code redirect_uri} 를 보내지 않는다. 코드는 5분 동안 한 번만 쓸 수
 * 있다. 애플이 4xx 로 거절하면 {@link Exchange.Rejected}(코드가 틀렸거나 만료됨), 응답하지 않거나 5xx 면
 * {@link Exchange.Unavailable} 이다. 코드·토큰·응답 본문은 로그에 남기지 않는다.
 */
final class AppleAppTokenClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppleAppTokenClient.class);

    private final RestClient restClient;
    private final String clientId;
    private final String tokenUri;
    private final AppleClientAssertion clientSecret;
    private final ObjectMapper objectMapper;

    AppleAppTokenClient(
        RestClient restClient,
        String clientId,
        String tokenUri,
        AppleClientAssertion clientSecret,
        ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.clientId = clientId;
        this.tokenUri = tokenUri;
        this.clientSecret = clientSecret;
        this.objectMapper = objectMapper;
    }

    Exchange exchange(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret.issue());
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);
        try {
            return restClient.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, response) -> {
                    final int status = response.getStatusCode().value();
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return tokensOf(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    }
                    LOGGER.warn("애플이 앱 인가 코드 교환을 거절했다. status={}", status);
                    if (response.getStatusCode().is4xxClientError()) {
                        return new Exchange.Rejected();
                    }
                    return new Exchange.Unavailable();
                });
        } catch (RuntimeException exception) {
            LOGGER.warn("애플 앱 인가 코드 교환을 부르지 못했다. cause={}", exception.getClass().getSimpleName());
            return new Exchange.Unavailable();
        }
    }

    private Exchange tokensOf(String body) {
        try {
            JsonNode tokens = objectMapper.readTree(body);
            String accessToken = tokens.path("access_token").asString("");
            String idToken = tokens.path("id_token").asString("");
            if (accessToken.isBlank() || idToken.isBlank()) {
                LOGGER.warn("애플 앱 인가 코드 교환 응답에 토큰이 없다");
                return new Exchange.Unavailable();
            }
            return new Exchange.Exchanged(accessToken, idToken);
        } catch (RuntimeException exception) {
            LOGGER.warn("애플 앱 인가 코드 교환 응답을 읽지 못했다. cause={}", exception.getClass().getSimpleName());
            return new Exchange.Unavailable();
        }
    }

    /** 교환 결과. 토큰 값은 이 요청 안에서만 쓰고 저장하지 않는다. */
    sealed interface Exchange {
        record Exchanged(String accessToken, String idToken) implements Exchange {
            @Override
            public String toString() {
                return "Exchanged[토큰 생략]";
            }
        }

        record Rejected() implements Exchange {
        }

        record Unavailable() implements Exchange {
        }
    }
}
