package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.worker.scheduling.PipelineForecastTrigger;
import com.gustler.backend.worker.scheduling.RouteLanes;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 예보의 노선별 실행 줄과 수집 신호·줍기를 등록한다. 예보가 켜져 있을 때({@code forecast.enabled=true})만 등록한다. 꺼져 있으면
 * 스레드도 빈도 스케줄도 만들지 않는다.
 *
 * <p>조건은 예보 모듈의 구성({@code ForecastMainlineConfiguration})과 같아야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastMainlineExecutorConfiguration {
    @Bean
    RouteLanes forecastRouteLanes(ForecastPipelineProperties properties, ObjectProvider<MeterRegistry> meters) {
        return new RouteLanes(properties.lanes(), properties.laneQueueCapacity(), meters.getIfAvailable());
    }

    @Bean
    PipelineForecastTrigger pipelineForecastTrigger(ForecastPipeline pipeline) {
        return new PipelineForecastTrigger(pipeline);
    }
}
