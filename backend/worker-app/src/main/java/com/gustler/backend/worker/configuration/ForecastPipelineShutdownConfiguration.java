package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.worker.scheduling.PipelineShutdown;
import com.gustler.backend.worker.scheduling.RouteLanes;
import com.gustler.backend.worker.scheduling.ScoringExecutor;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 예보의 정상 종료 처리를 등록한다. 예보가 켜져 있을 때({@code forecast.enabled=true})만 등록한다. 꺼져 있으면 빈을 만들지 않아
 * 종료 동작이 달라지지 않는다.
 *
 * <p>스케줄러, 노선별 실행 줄, 채점 줄, 채점 주기는 있는 것만 넘기므로 없는 단계는 건너뛴다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastPipelineShutdownConfiguration {
    @Bean
    PipelineShutdown pipelineShutdown(
        ForecastPipelineProperties properties,
        @Qualifier("collectionTaskScheduler") ObjectProvider<ThreadPoolTaskScheduler> collection,
        @Qualifier("forecastTaskScheduler") ObjectProvider<ThreadPoolTaskScheduler> pickup,
        @Qualifier("scoringTaskScheduler") ObjectProvider<ThreadPoolTaskScheduler> scoringTicks,
        ObjectProvider<RouteLanes> lanes,
        ObjectProvider<ScoringExecutor> scoring,
        ObjectProvider<ShadowScoringCycle> cycles
    ) {
        PipelineShutdown.Parts parts = new PipelineShutdown.Parts(present(collection), present(pickup),
            List.copyOf(present(lanes)), List.copyOf(present(scoring)), present(scoringTicks), present(cycles));
        return new PipelineShutdown(parts, properties.shutdownTimeout());
    }

    private static <T> List<T> present(ObjectProvider<T> provider) {
        return Stream.ofNullable(provider.getIfAvailable()).toList();
    }
}
