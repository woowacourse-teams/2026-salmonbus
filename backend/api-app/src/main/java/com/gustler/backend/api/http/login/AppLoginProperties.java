package com.gustler.backend.api.http.login;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 앱 네이티브 로그인 설정. 앱이 제공자 SDK 로 받은 ID 토큰의 대상({@code aud}) 값이다. 비밀이 아니다.
 *
 * <p>{@code appleClientId} 는 iOS 앱 번들 ID, {@code kakaoAppKey} 는 웹 로그인과 같은 카카오 앱의 네이티브 앱 키다.
 * 서버 하나는 그 환경의 앱 빌드 하나만 상대하므로 제공자마다 값 하나만 받는다. 값이 비어 있으면 그 제공자의 앱 로그인
 * 경로는 404 다. 기동을 막지 않는 것은 새 설정 없이 배포해도 기존 웹 로그인이 그대로 돌게 하려는 것이다.
 */
@ConfigurationProperties("auth.app")
public record AppLoginProperties(String appleClientId, String kakaoAppKey) {
}
