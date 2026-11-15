package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.worker.scheduling.ScoringExecutor;
import com.gustler.backend.worker.scheduling.ShadowScoringJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 채점의 줄·스레드·주기 작업을 등록한다. 예보가 켜져 있을 때({@code forecast.enabled=true})만 등록한다. 꺼져 있으면 스레드도 빈도
 * 스케줄도 만들지 않는다.
 *
 * <p>조건은 예보 모듈의 채점 구성({@code ForecastScoringConfiguration})과 같아야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastScoringExecutorConfiguration {
    @Bean
    ScoringExecutor scoringExecutor(ForecastPipelineProperties properties, ObjectProvider<MeterRegistry> meters) {
        return new ScoringExecutor(properties.scoringQueueCapacity(), meters.getIfAvailable());
    }

    @Bean(name = "scoringTaskScheduler")
    ThreadPoolTaskScheduler scoringTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(ScoringExecutor.THREAD_PREFIX + "tick-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    ShadowScoringJob shadowScoringJob(ShadowScoringCycle cycle) {
        return new ShadowScoringJob(cycle);
    }
}
