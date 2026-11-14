package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.pipeline.CompareShadowScoring;
import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.worker.scheduling.ScoringExecutor;
import com.gustler.backend.worker.scheduling.ShadowScoringJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 그림자 채점의 줄·스레드·주기 작업을 등록한다. 그림자 실행이 켜져 있고({@code forecast.enabled=true},
 * {@code forecast.shadow.enabled=true}, {@code forecast.pipeline=v1}) {@code forecast.scoring}이 {@code off}가 아닐 때만
 * 등록한다. 꺼져 있으면 스레드도 빈도 스케줄도 만들지 않는다. 그림자 실행 없이 {@code forecast.scoring}만 켜면 사건이 오지 않으므로
 * 등록하지 않는다({@link ForecastPipelineConfiguration}이 기동 때 경고를 남긴다).
 *
 * <p>조건은 예보 모듈의 채점 구성({@code ForecastScoringConfiguration.SCORING_ENABLED})과 같아야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnExpression(ForecastScoringExecutorConfiguration.SCORING_ENABLED)
public class ForecastScoringExecutorConfiguration {
    static final String SCORING_ENABLED = ForecastShadowExecutorConfiguration.SHADOW_ENABLED
        + " and !'${forecast.scoring:off}'.equalsIgnoreCase('off')";

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
    ShadowScoringJob shadowScoringJob(ShadowScoringCycle cycle, CompareShadowScoring comparison) {
        return new ShadowScoringJob(cycle, comparison);
    }
}
