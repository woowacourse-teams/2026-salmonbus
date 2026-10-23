package com.gustler.backend.api.http.login;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * 테스트용 카카오·애플 서버를 컨텍스트에 하나씩 띄우고, 로그인 설정의 제공자 주소를 그 서버로 돌린다.
 *
 * <p>서버 포트는 띄운 뒤에야 정해지므로 주소는 {@link DynamicPropertyRegistrar} 로 넣는다. 등록 여부를 가르는 스위치처럼
 * 조건 평가에 쓰이는 값은 이 시점에 보이지 않으므로 {@link LoginIntegrationTest} 의 고정 속성에 둔다. 서버는 컨텍스트가
 * 닫힐 때 함께 내린다. 서버에 남은 요청 기록과 응답 설정은 각 테스트가 시작할 때 {@code reset()} 으로 비운다.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeLoginProviders {
    @Bean(destroyMethod = "close")
    FakeKakaoServer fakeKakaoServer() throws Exception {
        return new FakeKakaoServer();
    }

    @Bean(destroyMethod = "close")
    FakeAppleServer fakeAppleServer() throws Exception {
        return new FakeAppleServer();
    }

    @Bean
    DynamicPropertyRegistrar fakeLoginProviderAddresses(FakeKakaoServer kakao, FakeAppleServer apple) {
        return registry -> {
            registry.add("auth.kakao.issuer", kakao::baseUrl);
            registry.add("auth.kakao.authorization-uri", () -> kakao.baseUrl() + "/oauth/authorize");
            registry.add("auth.kakao.token-uri", () -> kakao.baseUrl() + "/oauth/token");
            registry.add("auth.kakao.jwk-set-uri", () -> kakao.baseUrl() + "/jwks.json");
            registry.add("auth.kakao.unlink-uri", () -> kakao.baseUrl() + "/v1/user/unlink");
            registry.add("auth.apple.private-key-path", () -> apple.privateKeyFile().toString());
            registry.add("auth.apple.issuer", apple::baseUrl);
            registry.add("auth.apple.authorization-uri", () -> apple.baseUrl() + "/auth/authorize");
            registry.add("auth.apple.token-uri", () -> apple.baseUrl() + "/auth/token");
            registry.add("auth.apple.jwk-set-uri", () -> apple.baseUrl() + "/auth/keys");
            registry.add("auth.apple.revoke-uri", () -> apple.baseUrl() + "/auth/revoke");
        };
    }
}
