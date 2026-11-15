package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.pipeline.ForecastLanes;
import com.gustler.backend.forecasting.application.pipeline.ForecastPipelineService;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.ForecastStage;
import com.gustler.backend.forecasting.application.pipeline.PipelineBatchProcessor;
import com.gustler.backend.forecasting.application.pipeline.PipelineEvents;
import com.gustler.backend.forecasting.application.pipeline.RouteStateRegistry;
import com.gustler.backend.forecasting.application.pipeline.RouteStateReplayer;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.route.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.pipeline.CachedVehicleCapacities;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcPendingPipelineBatches;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcReplaySource;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcSettlementContextWriter;
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
 * 새 예보 경로를 본선으로 돌리는 구성 요소를 등록한다({@code forecast.pipeline=v2}).
 *
 * <p>예보가 켜져 있고({@code forecast.enabled=true}) {@code forecast.pipeline=v2}일 때만 등록한다. 이때 그림자 실행
 * ({@link ForecastShadowConfiguration})은 조건상 등록되지 않으므로 {@code forecast.shadow.enabled}는 무시된다. 노선별 실행 줄
 * ({@link ForecastLanes})과 신호·주기 호출은 실행하는 앱이 등록한다. 옛 발행 경로의 빈은 그대로 있지만 아무도 부르지 않는다.
 *
 * <p>다시 뜰 때 {@code forecast.replay-window}(기본 6시간)만큼 되감고, 되감은 상태가 모자라면
 * {@code forecast.replay-extended-window}(기본 12시간)로 한 번 늘려 다시 되감는다. 늘린 창은 이관이 최근 관측을 남겨 두는 창
 * ({@code archive.history-hold-window})보다 길 수 없다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(ForecastMainlineConfiguration.MAINLINE_ENABLED)
public class ForecastMainlineConfiguration {
    /** 본선을 켜는 조건. 실행하는 앱의 줄·신호 등록 조건과 같아야 한다. */
    public static final String MAINLINE_ENABLED = "${forecast.enabled:false}"
        + " and '${forecast.pipeline:v1}'.equalsIgnoreCase('v2')";
    private static final int WRITE_TIMEOUT_SECONDS = 15;

    @Bean
    JdbcReplaySource mainlineReplaySource(
        JdbcClient jdbc,
        @Value("${forecast.alighting.maximum-history-rows:250000}") final int maximumRows
    ) {
        return new JdbcReplaySource(jdbc, maximumRows, JdbcReplaySource.Axis.RECORDED_RUN);
    }

    @Bean
    ForecastPipelineService forecastPipelineService(
        ForecastLanes lanes,
        JdbcReplaySource mainlineReplaySource,
        JdbcClient jdbc,
        VehicleCapacityCache capacities,
        ForecastRecordStore records,
        StopPassStore passes,
        PlatformTransactionManager transactionManager,
        ObjectProvider<PipelineEvents> listeners,
        ObjectProvider<MeterRegistry> meters,
        ObjectProvider<ForecastTelemetry> telemetry,
        ForecastRuntime runtimes,
        RouteVersionQuery versions,
        RouteStopsQuery stops,
        ForecastPolicy policy,
        Clock clock,
        @Value("${forecast.replay-window:6h}") String replayWindow,
        @Value("${forecast.replay-extended-window:12h}") String replayExtendedWindow,
        @Value("${forecast.alighting.rush-only:true}") final boolean rushOnly
    ) {
        ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(capacities));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setTimeout(WRITE_TIMEOUT_SECONDS);
        RouteStateReplayer replayer = new RouteStateReplayer(mainlineReplaySource, stage,
            DurationStyle.detectAndParse(replayWindow), DurationStyle.detectAndParse(replayExtendedWindow));
        PipelineBatchProcessor processor = new PipelineBatchProcessor("pipeline_forecast", stage, replayer, records,
            passes, transactions, listeners.orderedStream().toList(), new RouteStateRegistry(), rushOnly);
        ForecastPipelineService service = new ForecastPipelineService(lanes, processor, mainlineReplaySource,
            new JdbcPendingPipelineBatches(jdbc), runtimes, versions, stops, new JdbcSettlementContextWriter(jdbc),
            policy, clock, meters.getIfAvailable());
        telemetry.ifAvailable(service::setTelemetry);
        return service;
    }
}
