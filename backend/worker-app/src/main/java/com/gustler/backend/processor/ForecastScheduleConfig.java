package com.gustler.backend.processor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 예보 쪽 배치를 배선한다. {@code forecast.enabled} 가 참일 때만 선다.
 *
 * <p>끄는 방식을 수집 쪽과 맞췄다. 두 배치가 서로 다른 방식으로 꺼져 있으면 나중에 켤 때
 * 한쪽을 빠뜨린다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ForecastProperties.class)
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastScheduleConfig {
}
