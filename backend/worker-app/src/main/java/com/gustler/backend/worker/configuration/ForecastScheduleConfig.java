package com.gustler.backend.worker.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 예보 배치를 구성한다. {@code forecast.enabled} 가 참일 때만 활성화한다.
 *
 * <p>비활성화 방식을 수집 배치와 맞췄다. 두 배치를 서로 다른 방식으로 비활성화하면 다시 켤 때
 * 한쪽을 빠뜨린다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ForecastProperties.class)
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastScheduleConfig {
}
