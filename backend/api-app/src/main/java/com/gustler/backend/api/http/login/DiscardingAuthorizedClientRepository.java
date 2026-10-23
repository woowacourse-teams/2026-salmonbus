package com.gustler.backend.api.http.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * 제공자가 발급한 토큰을 어디에도 저장하지 않는다.
 *
 * <p>기본 저장소는 액세스 토큰을 세션에 넣는다. 우리는 토큰을 로그인 확인에 쓰고 버린다. 다만 애플 탈퇴는 이번 요청에서 받은
 * 액세스 토큰으로 애플 연결을 끊어야 하므로, 토큰을 이 요청의 속성에만 둔다. 요청이 끝나면 함께 사라진다.
 */
final class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {
    private static final String ACCESS_TOKEN_ATTRIBUTE =
        DiscardingAuthorizedClientRepository.class.getName() + ".ACCESS_TOKEN";

    /** 이번 요청에서 제공자가 발급한 액세스 토큰. 토큰 교환이 없었던 요청이면 비어 있다. */
    static Optional<String> providerAccessToken(HttpServletRequest request) {
        if (request.getAttribute(ACCESS_TOKEN_ATTRIBUTE) instanceof String token) {
            return Optional.of(token);
        }
        return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
        String clientRegistrationId,
        Authentication principal,
        HttpServletRequest request
    ) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(
        OAuth2AuthorizedClient authorizedClient,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        request.setAttribute(ACCESS_TOKEN_ATTRIBUTE, authorizedClient.getAccessToken().getTokenValue());
    }

    @Override
    public void removeAuthorizedClient(
        String clientRegistrationId,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        request.removeAttribute(ACCESS_TOKEN_ATTRIBUTE);
    }
}
