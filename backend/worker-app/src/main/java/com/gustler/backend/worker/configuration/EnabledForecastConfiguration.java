package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.forecast.RunForecasts;
import com.gustler.backend.forecasting.api.forecast.ScoringCycle;
import com.gustler.backend.worker.scheduling.ForecastTrigger;
import com.gustler.backend.worker.scheduling.RouteLanes;
import com.gustler.backend.worker.scheduling.ScoringExecutor;
import com.gustler.backend.worker.scheduling.ScoringJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 예보를 켠 경우({@code forecast.enabled=true})에만 worker가 맡는 예보와 채점의 실행 부분을 등록한다. 설정에서 만든 정책, 노선별 실행
 * 줄과 수집 신호·줍기, 채점 줄과 그 주기 작업이다. 꺼져 있으면 설정을 읽지 않고 스레드도 빈도 스케줄도 만들지 않는다.
 *
 * <p>조건은 예보 모듈의 구성({@code ForecastScoringConfiguration})과 같아야 한다. 수집 구성과 같은 방식으로 끈다. 두 기능을 서로 다른
 * 방식으로 끄면 다시 켤 때 한쪽을 빠뜨린다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ForecastProperties.class)
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class EnabledForecastConfiguration {
    @Bean
    ForecastPolicy forecastPolicy(ForecastProperties properties, ConfigurableEnvironment environment) {
        BusinessConfiguration.requireStalenessFromConfigurationFile(environment);
        return new ForecastPolicy(properties.staleness(), properties.batchLimit());
    }

    @Bean
    RouteLanes forecastRouteLanes(ForecastProperties properties, ObjectProvider<MeterRegistry> meters) {
        return new RouteLanes(properties.lanes(), RouteLanes.QUEUE_CAPACITY, RouteLanes.STOP_WAIT,
            meters.getIfAvailable());
    }

    @Bean
    ForecastTrigger forecastTrigger(RunForecasts forecasts) {
        return new ForecastTrigger(forecasts);
    }

    @Bean
    ScoringExecutor scoringExecutor(ObjectProvider<MeterRegistry> meters) {
        return new ScoringExecutor(ScoringExecutor.QUEUE_CAPACITY, ScoringExecutor.STOP_WAIT, meters.getIfAvailable());
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
    ScoringJob scoringJob(ScoringCycle cycle) {
        return new ScoringJob(cycle);
    }
}
