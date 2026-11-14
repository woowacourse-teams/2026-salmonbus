package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.pipeline.CompareShadowScoring;
import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.worker.scheduling.ScoringComparisonJob;
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
 * 새 채점의 줄·스레드·주기 작업을 등록한다. {@code forecast.scoring}이 {@code off}가 아니고 사건을 낼 예보 경로가 돌 때만
 * 등록한다. 사건은 그림자 실행({@code forecast.enabled=true}, {@code forecast.shadow.enabled=true}, {@code forecast.pipeline=v1})이나
 * 본선({@code forecast.enabled=true}, {@code forecast.pipeline=v2})에서 온다. 꺼져 있으면 스레드도 빈도 스케줄도 만들지 않는다.
 *
 * <p>옛 정산과의 대조 작업({@link ScoringComparisonJob})은 옛 예보가 계속 나오는 {@code pipeline=v1}에서만 등록한다. 본선에서는 옛
 * 예측이 더 생기지 않아 대조할 것이 없다. 조건은 예보 모듈의 채점 구성({@code ForecastScoringConfiguration.SCORING_ENABLED},
 * {@code COMPARISON_ENABLED})과 같아야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnExpression(ForecastScoringExecutorConfiguration.SCORING_ENABLED)
public class ForecastScoringExecutorConfiguration {
    static final String SCORING_ENABLED = "(" + ForecastShadowExecutorConfiguration.SHADOW_ENABLED + " or "
        + ForecastMainlineExecutorConfiguration.MAINLINE_ENABLED + ") and !'${forecast.scoring:off}'.equalsIgnoreCase('off')";
    static final String COMPARISON_ENABLED = ForecastShadowExecutorConfiguration.SHADOW_ENABLED
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
    ShadowScoringJob shadowScoringJob(ShadowScoringCycle cycle) {
        return new ShadowScoringJob(cycle);
    }

    /** 옛 정산과의 대조 작업. {@code pipeline=v1}에서만 등록한다. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression(COMPARISON_ENABLED)
    static class Comparison {
        @Bean
        ScoringComparisonJob scoringComparisonJob(CompareShadowScoring comparison) {
            return new ScoringComparisonJob(comparison);
        }
    }
}
