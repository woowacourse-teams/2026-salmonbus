package com.gustler.backend.api.http.login;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 소셜 로그인 설정. 켜짐 스위치({@code auth.enabled}, {@code auth.<제공자>.enabled})는 빈 등록 조건이 읽는다.
 *
 * <p>{@code publicBaseUrl} 은 사용자가 보는 사이트 주소다. 제공자에 등록한 콜백 주소와 글자 하나까지 같아야 하므로
 * 요청에서 유추하지 않고 설정으로 받는다. 클라이언트 비밀은 환경 변수로만 주입한다.
 *
 * <p>애플은 고정 클라이언트 비밀이 없다. 팀 ID·키 ID·개인 키 파일 경로로 요청마다 비밀을 만든다. 개인 키는 설정에
 * 값을 담지 않고 파일 경로만 받는다.
 *
 * <p>애플의 {@code scopes} 에는 {@code openid} 가 있어야 한다. Spring Security 는 이 범위가 있어야 ID 토큰 흐름으로 처리한다.
 * 인가 요청은 {@code response_mode=query} 로 고정이므로 이름·이메일 범위는 넣지 않는다. 애플은 그 범위를 요청하면
 * {@code form_post} 를 요구하는 것으로 알려져 있고, 그러면 SameSite=Lax 세션 쿠키가 콜백에 실리지 않는다.
 */
@ConfigurationProperties("auth")
public record LoginProperties(String publicBaseUrl, Kakao kakao, Apple apple) {
    public record Kakao(
        String clientId,
        String clientSecret,
        String issuer,
        String authorizationUri,
        String tokenUri,
        String jwkSetUri
    ) {
    }

    public record Apple(
        String clientId,
        String teamId,
        String keyId,
        String privateKeyPath,
        List<String> scopes,
        String issuer,
        String authorizationUri,
        String tokenUri,
        String jwkSetUri,
        String revokeUri
    ) {
        private static final String OPENID = "openid";

        /** 켜진 상태에서 빠진 필수 설정이 있으면 어떤 환경 변수를 채워야 하는지 알려 주며 기동을 실패시킨다. */
        Apple requireComplete() {
            String missing = Stream.of(
                    new Setting("AUTH_APPLE_CLIENT_ID", clientId),
                    new Setting("AUTH_APPLE_TEAM_ID", teamId),
                    new Setting("AUTH_APPLE_KEY_ID", keyId),
                    new Setting("AUTH_APPLE_PRIVATE_KEY_PATH", privateKeyPath))
                .filter(setting -> setting.value() == null || setting.value().isBlank())
                .map(Setting::environmentVariable)
                .collect(Collectors.joining(", "));
            if (!missing.isEmpty()) {
                throw new IllegalStateException("auth.apple.enabled=true 이면 다음 설정이 필요하다: " + missing);
            }
            if (scopes == null || !scopes.contains(OPENID)) {
                throw new IllegalStateException("auth.apple.scopes 에는 openid 가 있어야 한다");
            }
            return this;
        }

        private record Setting(String environmentVariable, String value) {
        }
    }
}
