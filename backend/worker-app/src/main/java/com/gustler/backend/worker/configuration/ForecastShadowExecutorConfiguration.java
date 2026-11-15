package com.gustler.backend.worker.configuration;

import com.gustler.backend.worker.scheduling.ShadowForecastExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 그림자 실행 줄을 등록한다. 옛 경로가 돌고({@code forecast.enabled=true}) 본선이 옛 경로({@code forecast.pipeline=v1})이며
 * {@code forecast.shadow.enabled=true}일 때만 등록한다. 꺼져 있으면 스레드도 빈도 만들지 않는다.
 *
 * <p>조건은 예보 모듈의 그림자 구성({@code ForecastShadowConfiguration.SHADOW_ENABLED})과 같아야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnExpression(ForecastShadowExecutorConfiguration.SHADOW_ENABLED)
public class ForecastShadowExecutorConfiguration {
    static final String SHADOW_ENABLED = "${forecast.enabled:false} and ${forecast.shadow.enabled:false}"
        + " and '${forecast.pipeline:v1}'.equalsIgnoreCase('v1')";

    @Bean
    ShadowForecastExecutor shadowForecastExecutor(
        ForecastPipelineProperties properties,
        ObjectProvider<MeterRegistry> meters
    ) {
        return new ShadowForecastExecutor(properties.shadow().queueCapacity(), meters.getIfAvailable());
    }
}
