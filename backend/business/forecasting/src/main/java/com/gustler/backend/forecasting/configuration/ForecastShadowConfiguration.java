package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.api.pipeline.ShadowForecastFeed;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.ForecastStage;
import com.gustler.backend.forecasting.application.pipeline.PipelineEvents;
import com.gustler.backend.forecasting.application.pipeline.RouteStateRegistry;
import com.gustler.backend.forecasting.application.pipeline.RouteStateReplayer;
import com.gustler.backend.forecasting.application.pipeline.ShadowForecastService;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.pipeline.CachedVehicleCapacities;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcReplaySource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 옛 경로 옆에서 새 예보 경로를 돌리는 그림자 실행의 구성 요소를 등록한다.
 *
 * <p>옛 경로가 돌고({@code forecast.enabled=true}) 본선이 옛 경로이며({@code forecast.pipeline=v1})
 * {@code forecast.shadow.enabled=true}일 때만 등록한다. 꺼져 있으면 빈이 하나도 없어 옛 경로는 알림을 보내지 않는다.
 * 일을 실행할 줄({@link ShadowForecastFeed})은 실행하는 앱이 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(ForecastShadowConfiguration.SHADOW_ENABLED)
public class ForecastShadowConfiguration {
    /** 그림자 실행을 켜는 조건. 실행하는 앱의 줄 등록 조건과 같아야 한다. */
    public static final String SHADOW_ENABLED = "${forecast.enabled:false} and ${forecast.shadow.enabled:false}"
        + " and '${forecast.pipeline:v1}'.equalsIgnoreCase('v1')";
    private static final int WRITE_TIMEOUT_SECONDS = 15;

    @Bean
    JdbcReplaySource shadowReplaySource(
        JdbcClient jdbc,
        @Value("${forecast.alighting.maximum-history-rows:250000}") final int maximumRows
    ) {
        return new JdbcReplaySource(jdbc, maximumRows);
    }

    @Bean
    ShadowForecastService shadowForecastService(
        ShadowForecastFeed feed,
        JdbcReplaySource shadowReplaySource,
        VehicleCapacityCache capacities,
        ForecastRecordStore records,
        StopPassStore passes,
        PlatformTransactionManager transactionManager,
        ObjectProvider<PipelineEvents> listeners,
        ObjectProvider<MeterRegistry> meters,
        Clock clock,
        @Value("${forecast.replay-window:6h}") String replayWindow,
        @Value("${forecast.alighting.rush-only:true}") final boolean rushOnly
    ) {
        ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(capacities));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setTimeout(WRITE_TIMEOUT_SECONDS);
        return new ShadowForecastService(feed, stage, new RouteStateReplayer(shadowReplaySource, stage,
            DurationStyle.detectAndParse(replayWindow)),
            shadowReplaySource, records, passes, transactions, listeners.orderedStream().toList(), new RouteStateRegistry(),
            rushOnly, clock, meters.getIfAvailable());
    }
}
