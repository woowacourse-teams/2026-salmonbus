package com.gustler.backend.api.account.token;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 액세스 토큰 설정.
 *
 * <p>{@code secret} 은 환경 변수로만 주입한다. 서명과 검증을 모두 이 API가 하므로 대칭 키(HS256) 하나면 된다.
 * 키를 바꿔도 리프레시 토큰은 영향을 받지 않아, 발급된 액세스 토큰만 무효가 되고 클라이언트가 갱신으로 이어 간다.
 */
@ConfigurationProperties("auth.token")
public record TokenProperties(String secret, String keyId, Duration accessTtl) {
    private static final int MINIMUM_SECRET_BYTES = 32;

    /** 로그인이 켜졌을 때만 부르는 검증. 짧거나 빈 비밀로는 기동하지 않는다. */
    TokenProperties requireComplete() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException("auth.enabled=true 이면 AUTH_TOKEN_SECRET 이 32바이트 이상이어야 한다");
        }
        return this;
    }
}
