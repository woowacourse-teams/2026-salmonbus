package com.gustler.backend.api.account.unlink;

import com.gustler.backend.member.api.ProviderUnlinks;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 탈퇴한 회원의 제공자 연결 해제 도구를 만든다. 로그인이 켜졌을 때만 등록한다.
 *
 * <p>카카오 해제 클라이언트는 카카오 로그인이 켜졌을 때만 만들고 어드민 키가 없으면 기동하지 않는다. 키 없이 켜 두면 카카오 회원의
 * 탈퇴가 모두 해제 실패로 쌓인다. 제공자 호출은 제한 시간을 두어 응답하지 않아도 탈퇴 요청이 매달리지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@EnableConfigurationProperties(KakaoUnlinkProperties.class)
public class UnlinkConfiguration {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    @ConditionalOnProperty(prefix = "auth", name = "kakao.enabled", havingValue = "true")
    KakaoUnlinkClient kakaoUnlinkClient(KakaoUnlinkProperties properties, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        return new KakaoUnlinkClient(restClient, properties.requireComplete(), objectMapper);
    }

    @Bean
    ProviderUnlinkRunner providerUnlinkRunner(
        ProviderUnlinks unlinks,
        ObjectProvider<KakaoUnlinkClient> kakao,
        MeterRegistry meters
    ) {
        return new ProviderUnlinkRunner(unlinks, kakao, meters);
    }

    @Bean
    ProviderUnlinkRetryJob providerUnlinkRetryJob(ProviderUnlinks unlinks, ProviderUnlinkRunner runner) {
        return new ProviderUnlinkRetryJob(unlinks, runner);
    }
}
