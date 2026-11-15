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
 * 새 예보 경로가 넘긴 사건으로 새 채점을 돌리는 구성 요소를 등록한다.
 *
 * <p>{@code forecast.scoring}이 {@code off}가 아니고 사건을 낼 예보 경로가 돌 때만 등록한다. 사건은 그림자 예보
 * ({@link ForecastShadowConfiguration#SHADOW_ENABLED}, {@code pipeline=v1})나
 * 본선({@link ForecastMainlineConfiguration#MAINLINE_ENABLED}, {@code pipeline=v2})에서 온다. 두 경로 모두 같은
 * {@link PipelineEvents} 구현(이 채점)에 사건을 넘긴다. {@code shadow}와 {@code v2}의 채점 동작은 같다. 다른 것은 옛 정산 작업을 실행하는 앱이 돌리는지
 * ({@code v2}면 돌리지 않는다)와 옛 정산과의 대조다. 대조({@link JdbcScoringComparison})는 옛 예보가 계속 나오는
 * {@code pipeline=v1}에서만 등록한다. 일을 실행할 줄({@link ScoringFeed})과 주기 작업은 실행하는 앱이 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(ForecastScoringConfiguration.SCORING_ENABLED)
public class ForecastScoringConfiguration {
    /** 채점을 켜는 조건. 실행하는 앱의 줄·주기 작업 등록 조건과 같아야 한다. */
    public static final String SCORING_ENABLED = "(" + ForecastShadowConfiguration.SHADOW_ENABLED + " or "
        + ForecastMainlineConfiguration.MAINLINE_ENABLED + ") and !'${forecast.scoring:off}'.equalsIgnoreCase('off')";
    /** 옛 정산과의 대조를 켜는 조건. 채점이 켜져 있고 본선이 옛 경로일 때다. */
    public static final String COMPARISON_ENABLED = ForecastShadowConfiguration.SHADOW_ENABLED
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
        ScoringStage stage = new ScoringStage(new JdbcSettlementContextProvider(jdbc),
            new JdbcRouteStructureProvider(jdbc), new ConfirmedPassLabelCalculator(), clock, observationLimit);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setTimeout(WRITE_TIMEOUT_SECONDS);
        ScoringReplay replay = new ScoringReplay(records, passes);
        return new ShadowScoringService(feed, stage, replay, new ScoringWriter(labels, transactions), records,
            replay.maxForecastWindow(), clock, meters.getIfAvailable());
    }

    /** 옛 정산과의 대조. 옛 예보가 계속 나오는 {@code pipeline=v1}에서만 등록한다. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression(COMPARISON_ENABLED)
    static class Comparison {
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
}
