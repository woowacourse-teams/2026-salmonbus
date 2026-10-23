package com.gustler.backend.api.account.token;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 로그인이 켜졌을 때만 토큰 도구를 만든다. 꺼져 있으면 비밀이 없어도 기동한다.
 *
 * <p>만료된 계통을 지우는 주기 작업({@link RefreshTokenPurge})을 위해 스케줄링을 켠다. 다른 기능이 켜 주는 것에 기대면
 * 그 기능이 꺼진 환경에서 작업이 조용히 돌지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TokenProperties.class)
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@EnableScheduling
public class TokenConfiguration {
    @Bean
    AccessTokens accessTokens(TokenProperties properties, Clock clock) {
        return new AccessTokens(properties.requireComplete(), clock);
    }

    @Bean
    WithdrawalTickets withdrawalTickets(TokenProperties properties, Clock clock) {
        return new WithdrawalTickets(properties.requireComplete(), clock);
    }

    @Bean
    AuthCookies authCookies(TokenProperties properties, Clock clock) {
        return new AuthCookies(properties, clock);
    }
}
