package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import com.gustler.backend.worker.scheduling.DrainableQueue;
import com.gustler.backend.worker.scheduling.PipelineShutdown;
import com.gustler.backend.worker.scheduling.RouteLanes;
import com.gustler.backend.worker.scheduling.ScoringExecutor;
import com.gustler.backend.worker.scheduling.ShadowForecastExecutor;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 새 예보 경로의 정상 종료 처리를 등록한다. 그림자 실행이나 본선이 돌 때만 등록한다. 스위치가 기본값이면 빈을 만들지 않아 종료
 * 동작이 달라지지 않는다.
 *
 * <p>그림자 실행 줄, 본선의 노선별 실행 줄, 채점 줄과 주기 작업은 스위치 조합에 따라 있거나 없다. 있는 것만 넘기므로 없는 단계는
 * 건너뛴다. 조건은 {@link ForecastShadowExecutorConfiguration}과 {@link ForecastMainlineExecutorConfiguration}의 합이고, 채점은 그
 * 둘 가운데 하나가 돌 때만 켜진다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnExpression(ForecastPipelineShutdownConfiguration.RUNS_PIPELINE)
public class ForecastPipelineShutdownConfiguration {
    static final String RUNS_PIPELINE = "(" + ForecastShadowExecutorConfiguration.SHADOW_ENABLED + ") or ("
        + ForecastMainlineExecutorConfiguration.MAINLINE_ENABLED + ")";

    @Bean
    PipelineShutdown pipelineShutdown(
        ForecastPipelineProperties properties,
        @Qualifier("collectionTaskScheduler") ObjectProvider<ThreadPoolTaskScheduler> collection,
        @Qualifier("forecastTaskScheduler") ObjectProvider<ThreadPoolTaskScheduler> pickup,
        @Qualifier("scoringTaskScheduler") ObjectProvider<ThreadPoolTaskScheduler> scoringTicks,
        ObjectProvider<RouteLanes> lanes,
        ObjectProvider<ShadowForecastExecutor> shadow,
        ObjectProvider<ScoringExecutor> scoring,
        ObjectProvider<ShadowScoringCycle> cycles
    ) {
        List<DrainableQueue> forecast = Stream.<DrainableQueue>concat(present(lanes).stream(),
            present(shadow).stream()).toList();
        PipelineShutdown.Parts parts = new PipelineShutdown.Parts(present(collection), present(pickup), forecast,
            List.copyOf(present(scoring)), present(scoringTicks), present(cycles));
        return new PipelineShutdown(parts, properties.shutdownTimeout());
    }

    private static <T> List<T> present(ObjectProvider<T> provider) {
        return Stream.ofNullable(provider.getIfAvailable()).toList();
    }
}
