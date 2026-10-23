package com.gustler.backend.api.http.login;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 애플이 발급한 토큰을 취소해 우리 앱과 애플 계정의 연결을 끊는다.
 *
 * <p>애플은 계정을 삭제할 때 토큰 취소를 요구한다. 토큰은 탈퇴 재인증에서 방금 받은 액세스 토큰이다. 실패는 예외 대신
 * {@code false} 로 알린다. 호출자는 실패하면 탈퇴를 멈추고 회원을 남긴다. 토큰과 응답 본문은 로그에 남기지 않는다.
 */
final class AppleRevokeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppleRevokeClient.class);

    private final RestClient restClient;
    private final String clientId;
    private final String revokeUri;
    private final AppleClientAssertion clientSecret;

    AppleRevokeClient(
        RestClient restClient,
        String clientId,
        String revokeUri,
        AppleClientAssertion clientSecret
    ) {
        this.restClient = restClient;
        this.clientId = clientId;
        this.revokeUri = revokeUri;
        this.clientSecret = clientSecret;
    }

    boolean revoke(String accessToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret.issue());
        form.add("token", accessToken);
        form.add("token_type_hint", "access_token");
        try {
            return restClient.post()
                .uri(revokeUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, response) -> {
                    final boolean revoked = response.getStatusCode().is2xxSuccessful();
                    if (!revoked) {
                        LOGGER.warn("애플이 토큰 취소를 거절했다. status={}", response.getStatusCode().value());
                    }
                    return revoked;
                });
        } catch (RuntimeException exception) {
            LOGGER.warn("애플 토큰 취소를 부르지 못했다. cause={}", exception.getClass().getSimpleName());
            return false;
        }
    }
}
