package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.worker.scheduling.PipelineForecastTrigger;
import com.gustler.backend.worker.scheduling.RouteLanes;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 새 예보 경로를 본선으로 돌리는 실행 줄과 신호를 등록한다. 예보가 켜져 있고({@code forecast.enabled=true})
 * {@code forecast.pipeline=v2}일 때만 등록한다. 꺼져 있으면 스레드도 빈도 스케줄도 만들지 않는다.
 *
 * <p>조건은 예보 모듈의 본선 구성({@code ForecastMainlineConfiguration.MAINLINE_ENABLED})과 같아야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnExpression(ForecastMainlineExecutorConfiguration.MAINLINE_ENABLED)
public class ForecastMainlineExecutorConfiguration {
    static final String MAINLINE_ENABLED = "${forecast.enabled:false}"
        + " and '${forecast.pipeline:v1}'.equalsIgnoreCase('v2')";

    @Bean
    RouteLanes forecastRouteLanes(ForecastPipelineProperties properties, ObjectProvider<MeterRegistry> meters) {
        return new RouteLanes(properties.lanes(), properties.laneQueueCapacity(), meters.getIfAvailable());
    }

    @Bean
    PipelineForecastTrigger pipelineForecastTrigger(ForecastPipeline pipeline) {
        return new PipelineForecastTrigger(pipeline);
    }
}
