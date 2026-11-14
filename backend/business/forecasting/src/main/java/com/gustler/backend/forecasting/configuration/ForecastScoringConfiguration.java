package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.api.pipeline.ScoringFeed;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.ScoringReplay;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.application.pipeline.ScoringWriter;
import com.gustler.backend.forecasting.application.pipeline.ShadowScoringService;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.infrastructure.jdbc.ConfirmedPassLabelCalculator;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcForecastRecordStore;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcRouteStructureProvider;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcScoringComparison;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcSettlementContextProvider;
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
 * 그림자 예보가 넘긴 사건으로 새 채점을 돌리는 그림자 채점({@code forecast.scoring=shadow})의 구성 요소를 등록한다.
 *
 * <p>그림자 예보가 켜져 있고({@link ForecastShadowConfiguration#SHADOW_ENABLED}) {@code forecast.scoring}이 {@code off}가
 * 아닐 때만 등록한다. 사건은 그림자 예보에서만 오므로 그림자 예보가 꺼져 있으면 채점도 켜지 않는다. {@code forecast.scoring=v2}는
 * 이 묶음에서 {@code shadow}와 똑같이 동작한다(옛 정산을 끄는 것은 다음 묶음이 한다). 일을 실행할 줄({@link ScoringFeed})과
 * 주기 작업은 실행하는 앱이 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(ForecastScoringConfiguration.SCORING_ENABLED)
public class ForecastScoringConfiguration {
    /** 그림자 채점을 켜는 조건. 실행하는 앱의 줄·주기 작업 등록 조건과 같아야 한다. */
    public static final String SCORING_ENABLED = ForecastShadowConfiguration.SHADOW_ENABLED
        + " and !'${forecast.scoring:off}'.equalsIgnoreCase('off')";
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
        ScoringStage stage = new ScoringStage(new JdbcSettlementContextProvider(jdbc, clock),
            new JdbcRouteStructureProvider(jdbc), new ConfirmedPassLabelCalculator(), clock, observationLimit);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setTimeout(WRITE_TIMEOUT_SECONDS);
        return new ShadowScoringService(feed, stage, new ScoringReplay(records, passes),
            new ScoringWriter(labels, transactions), records, ScoringReplay.DEFAULT_FORECAST_WINDOW, clock,
            meters.getIfAvailable());
    }

    @Bean
    JdbcScoringComparison scoringComparison(
        JdbcClient jdbc,
        Clock clock,
        ObjectProvider<MeterRegistry> meters,
        @Value("${forecast.scoring-comparison-delay:3h}") String delay,
        @Value("${forecast.scoring-comparison-batch-limit:1500}") final int batchLimit
    ) {
        return new JdbcScoringComparison(jdbc, clock, DurationStyle.detectAndParse(delay),
            ScoringReplay.DEFAULT_FORECAST_WINDOW, batchLimit, meters.getIfAvailable());
    }
}
