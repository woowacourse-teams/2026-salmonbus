package com.gustler.backend.api.http.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * 제공자가 발급한 토큰을 어디에도 저장하지 않는다.
 *
 * <p>기본 저장소는 액세스 토큰을 세션에 넣는다. 우리는 토큰으로 제공자 API를 다시 부르지 않고
 * 로그인 확인에만 쓰고 버린다.
 */
final class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {
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
    }

    @Override
    public void removeAuthorizedClient(
        String clientRegistrationId,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
    }
}
