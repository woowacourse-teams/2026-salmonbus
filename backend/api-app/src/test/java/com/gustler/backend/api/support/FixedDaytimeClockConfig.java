package com.gustler.backend.api.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 앱의 시계를 서울 기준 2026-08-27 12:00 로 고정한다.
 *
 * <p>같은 낮 시각을 쓰는 계약 시험이 이 설정을 함께 가져와 한 컨텍스트를 같이 쓴다. 시각을 바꾸려면 이 설정을 쓰는
 * 시험이 모두 영향을 받는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedDaytimeClockConfig {
    @Bean
    @Primary
    Clock fixedDaytimeClock() {
        return Clock.fixed(Instant.parse("2026-08-27T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    }
}
