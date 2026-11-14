package com.gustler.backend.worker.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 예보가 켜진 worker({@code forecast.enabled=true})에서 새 예보 경로 스위치의 조합을 기동 때 확인한다.
 *
 * <p>받아들이지 않는 조합이면 기동을 멈춘다({@link ForecastPipelineProperties#requireSupportedCombination()}). 예보를 끈 worker에서는
 * 스위치가 아무 일도 하지 않으므로 확인하지 않는다. 빈 하나만 더하고 스케줄·쓰기는 없다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastPipelineSwitchConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastPipelineSwitchConfiguration.class);

    @Bean
    InitializingBean forecastPipelineSwitchCheck(ForecastPipelineProperties properties) {
        return () -> {
            properties.requireSupportedCombination();
            if (properties.shadowIgnored()) {
                LOGGER.warn("event=pipeline_shadow_ignored pipeline={} shadowEnabled=true", properties.pipeline());
            }
        };
    }
}
