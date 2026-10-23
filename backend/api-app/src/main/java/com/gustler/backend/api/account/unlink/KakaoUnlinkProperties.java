package com.gustler.backend.api.account.unlink;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 연결 해제 설정.
 *
 * <p>{@code adminKey} 는 카카오 앱의 어드민 키다. 앱의 모든 사용자에 대해 연결 해제 같은 관리 API를 부를 수 있는 키라
 * 환경 변수로만 주입하고 로그에 남기지 않는다.
 */
@ConfigurationProperties("auth.kakao")
public record KakaoUnlinkProperties(String adminKey, String unlinkUri) {
    KakaoUnlinkProperties requireComplete() {
        if (adminKey == null || adminKey.isBlank()) {
            throw new IllegalStateException("auth.kakao.enabled=true 이면 AUTH_KAKAO_ADMIN_KEY 가 필요하다");
        }
        return this;
    }
}
