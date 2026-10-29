package com.gustler.backend.api.http.login;

import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.WithdrawMember;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 애플 서버 간 알림을 받는 도구를 만든다. 로그인과 애플이 모두 켜졌을 때만 등록한다.
 *
 * <p>발급자와 공개키 주소는 웹 로그인 설정을 그대로 쓴다. 대상은 웹 Services ID({@code auth.apple.client-id})와, 값이
 * 있으면 앱 번들 ID({@code auth.app.apple-client-id})다. 번들 ID 가 비어 있으면 대상이 번들 ID 인 알림은 거절한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "auth", name = {"enabled", "apple.enabled"}, havingValue = "true")
public class AppleNotificationConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppleNotificationConfiguration.class);

    @Bean
    AppleNotificationVerifier appleNotificationVerifier(
        LoginProperties properties,
        AppLoginProperties app,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        LoginProperties.Apple apple = properties.apple();
        String appClientId = app.appleClientId();
        if (appClientId == null || appClientId.isBlank()) {
            LOGGER.warn("앱 번들 ID(AUTH_APPLE_APP_ID)가 비어 있어 대상이 번들 ID인 애플 서버 간 알림은 모두 거절한다");
        }
        Set<String> audiences = Stream.of(apple.clientId(), appClientId)
            .filter(audience -> audience != null && !audience.isBlank())
            .collect(Collectors.toUnmodifiableSet());
        return AppleNotificationVerifier.create(apple.issuer(), audiences, apple.jwkSetUri(), objectMapper, clock);
    }

    @Bean
    AppleNotifications appleNotifications(
        WithdrawMember withdrawMember,
        RefreshTokens refreshTokens,
        MeterRegistry meters
    ) {
        return new AppleNotifications(withdrawMember, refreshTokens, meters);
    }
}
