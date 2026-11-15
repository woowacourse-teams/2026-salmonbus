package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.pipeline.ForecastLanes;
import com.gustler.backend.forecasting.api.pipeline.ScoringFeed;
import com.gustler.backend.forecasting.application.pipeline.ForecastPipelineService;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.PipelineEvents;
import com.gustler.backend.forecasting.application.pipeline.ShadowScoringService;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.route.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcForecastRecordStore;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcPendingPipelineBatches;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcReplaySource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 예보와 채점을 등록한다. 예보가 켜져 있을 때({@code forecast.enabled=true})만 등록하며 둘은 늘 함께 켜진다.
 *
 * <p>예보는 커밋한 뒤의 사건을 {@link PipelineEvents} 구현인 채점에 넘긴다. 노선별 실행 줄({@link ForecastLanes}), 채점
 * 줄({@link ScoringFeed}), 신호와 주기 호출은 실행하는 앱이 같은 조건으로 등록한다. 묶는 방법과 되감기 창 같은 고정 값은
 * {@link ForecastAssembly}에 있다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastScoringConfiguration {
    @Bean
    ShadowScoringService shadowScoringService(
        ScoringFeed feed,
        JdbcClient jdbc,
        JdbcForecastRecordStore records,
        StopPassStore passes,
        PassLabelStore labels,
        PlatformTransactionManager transactionManager,
        ObjectProvider<MeterRegistry> meters,
        Clock clock
    ) {
        return new ForecastAssembly(jdbc, transactionManager, clock, meters.getIfAvailable())
            .scoring(feed, records, records, passes, labels);
    }

    @Bean
    ForecastPipelineService forecastPipelineService(
        ForecastLanes lanes,
        PipelineEvents events,
        JdbcClient jdbc,
        VehicleCapacityCache capacities,
        ForecastRecordStore records,
        StopPassStore passes,
        PlatformTransactionManager transactionManager,
        ObjectProvider<MeterRegistry> meters,
        ObjectProvider<ForecastTelemetry> telemetry,
        ForecastRuntime runtimes,
        RouteVersionQuery versions,
        RouteStopsQuery stops,
        ForecastPolicy policy,
        Clock clock
    ) {
        JdbcReplaySource source = new JdbcReplaySource(jdbc, JdbcReplaySource.DEFAULT_MAXIMUM_ROWS);
        ForecastAssembly.Sources sources = new ForecastAssembly.Sources(source, source,
            new JdbcPendingPipelineBatches(jdbc), runtimes, versions, stops);
        ForecastPipelineService service = new ForecastAssembly(jdbc, transactionManager, clock, meters.getIfAvailable())
            .forecast(lanes, events, sources, capacities, records, passes, ForecastAssembly.Settings.of(policy));
        telemetry.ifAvailable(service::setTelemetry);
        return service;
    }
}
