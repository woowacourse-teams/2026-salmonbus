package com.gustler.backend.member.configuration;

import com.gustler.backend.member.domain.RefreshTokenCodec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 리프레시 토큰 서명 비밀을 설정에서 읽어 코덱을 만든다.
 *
 * <p>비밀은 {@code auth.refresh-token.secret}(환경 변수 {@code AUTH_REFRESH_TOKEN_SECRET})이고 UTF-8 로
 * 32바이트 이상이어야 한다. 로그인이 켜져 있는데({@code auth.enabled=true}) 비밀이 짧거나 없으면 기동에 실패한다.
 * 로그인이 꺼져 있으면 비밀 없이도 기동하고, 프로세스가 사는 동안만 쓰는 무작위 키를 쓴다. 그 키로 서명한 토큰은
 * 재시작하면 모두 무효가 되지만 로그인이 꺼져 있어 토큰을 발급할 일이 없다.
 */
@Configuration(proxyBeanMethods = false)
public class RefreshTokenConfiguration {
    static final int MIN_SECRET_BYTES = 32;

    @Bean
    RefreshTokenCodec refreshTokenCodec(Environment environment) {
        if (!environment.getProperty("auth.enabled", Boolean.class, false)) {
            return new RefreshTokenCodec(randomSecret());
        }
        byte[] secret = environment.getProperty("auth.refresh-token.secret", "").getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "AUTH_REFRESH_TOKEN_SECRET 은 UTF-8 기준 %d바이트 이상이어야 한다".formatted(MIN_SECRET_BYTES));
        }
        return new RefreshTokenCodec(secret);
    }

    private static byte[] randomSecret() {
        byte[] secret = new byte[MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        return secret;
    }
}
