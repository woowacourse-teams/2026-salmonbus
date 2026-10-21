package com.gustler.backend.api.http.login;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 소셜 로그인 설정. 켜짐 스위치({@code auth.enabled}, {@code auth.<제공자>.enabled})는 빈 등록 조건이 읽는다.
 *
 * <p>{@code publicBaseUrl} 은 사용자가 보는 사이트 주소다. 제공자에 등록한 콜백 주소와 글자 하나까지 같아야 하므로
 * 요청에서 유추하지 않고 설정으로 받는다. 클라이언트 비밀은 환경 변수로만 주입한다.
 */
@ConfigurationProperties("auth")
public record LoginProperties(String publicBaseUrl, Kakao kakao) {
    public record Kakao(
        String clientId,
        String clientSecret,
        String issuer,
        String authorizationUri,
        String tokenUri,
        String jwkSetUri
    ) {
    }
}
