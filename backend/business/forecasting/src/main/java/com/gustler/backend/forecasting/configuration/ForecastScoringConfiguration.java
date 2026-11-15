package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.api.pipeline.ScoringFeed;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.PipelineEvents;
import com.gustler.backend.forecasting.application.pipeline.ScoringReplay;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.application.pipeline.ScoringWriter;
import com.gustler.backend.forecasting.application.pipeline.ShadowScoringService;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.infrastructure.jdbc.ConfirmedPassLabelCalculator;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcForecastRecordStore;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcRouteStructureProvider;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcSettlementContextProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예보가 넘긴 사건으로 채점을 돌리는 구성 요소를 등록한다.
 *
 * <p>예보가 켜져 있을 때({@code forecast.enabled=true})만 등록한다. 예보는 커밋한 뒤의 사건을 {@link PipelineEvents} 구현(이 채점)에
 * 넘긴다. 일을 실행할 줄({@link ScoringFeed})과 주기 작업은 실행하는 앱이 같은 조건으로 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastScoringConfiguration {
    private static final int WRITE_TIMEOUT_SECONDS = 15;

    @Bean
    ShadowScoringService shadowScoringService(
        ScoringFeed feed,
        JdbcClient jdbc,
        JdbcForecastRecordStore records,
        StopPassStore passes,
        PassLabelStore labels,
        PlatformTransactionManager transactionManager,
        ObjectProvider<MeterRegistry> meters,
        Clock clock,
        @Value("${forecast.alighting.causal-evaluation.maximum-observation-rows:150000}") final int observationLimit
    ) {
        ScoringStage stage = new ScoringStage(new JdbcSettlementContextProvider(jdbc),
            new JdbcRouteStructureProvider(jdbc), new ConfirmedPassLabelCalculator(), clock, observationLimit);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setTimeout(WRITE_TIMEOUT_SECONDS);
        ScoringReplay replay = new ScoringReplay(records, passes);
        return new ShadowScoringService(feed, stage, replay, new ScoringWriter(labels, transactions), records,
            replay.maxForecastWindow(), clock, meters.getIfAvailable());
    }
}
