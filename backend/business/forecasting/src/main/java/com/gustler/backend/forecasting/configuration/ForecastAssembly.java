package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.forecast.ForecastLanes;
import com.gustler.backend.forecasting.api.forecast.ScoringFeed;
import com.gustler.backend.forecasting.application.forecast.ForecastBatchProcessor;
import com.gustler.backend.forecasting.application.forecast.ForecastEvents;
import com.gustler.backend.forecasting.application.forecast.ForecastRecordStore;
import com.gustler.backend.forecasting.application.forecast.ForecastStage;
import com.gustler.backend.forecasting.application.forecast.ObservationBatchReader;
import com.gustler.backend.forecasting.application.forecast.PassLabelStore;
import com.gustler.backend.forecasting.application.forecast.PendingBatches;
import com.gustler.backend.forecasting.application.forecast.RecentForecastRoutes;
import com.gustler.backend.forecasting.application.forecast.ReplaySource;
import com.gustler.backend.forecasting.application.forecast.RouteStateRegistry;
import com.gustler.backend.forecasting.application.forecast.RouteStateReplayer;
import com.gustler.backend.forecasting.application.forecast.RunForecastsService;
import com.gustler.backend.forecasting.application.forecast.ScoringReplay;
import com.gustler.backend.forecasting.application.forecast.ScoringService;
import com.gustler.backend.forecasting.application.forecast.ScoringStage;
import com.gustler.backend.forecasting.application.forecast.ScoringWriter;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.route.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import com.gustler.backend.forecasting.infrastructure.capacity.CachedVehicleCapacities;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.jdbc.AlightingAwarePassLabelCalculator;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcRouteStructureProvider;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcSettlementContextProvider;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcSettlementContextWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예보와 채점을 구성 요소로 묶는다. worker의 구성({@link ForecastScoringConfiguration})과, 같은 묶음을 스프링 없이 돌리는 테스트·모델
 * 번들 검증 도구가 함께 쓴다. 구성 요소의 생성자가 바뀌면 여기만 고친다.
 *
 * <p>부르는 쪽은 실행 줄, 시계, 조회({@link Sources}), 조절값({@link Settings})처럼 환경마다 다른 입력만 넘긴다. 저장 트랜잭션 하나는
 * {@value #WRITE_TIMEOUT_SECONDS}초로 제한한다.
 */
public final class ForecastAssembly {
    private static final int WRITE_TIMEOUT_SECONDS = 15;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry meters;

    /**
     * @param jdbc 모델 배포의 정산 근거와 노선 구조를 읽고 쓰는 DB
     * @param transactionManager 예보 저장과 채점 쓰기의 트랜잭션
     * @param clock 착수 시각, 되감기 기준 시각, 마감 시각의 출처
     * @param meters 지표 등록기. 없으면 지표를 내지 않는다
     */
    public ForecastAssembly(
        JdbcClient jdbc,
        PlatformTransactionManager transactionManager,
        Clock clock,
        MeterRegistry meters
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transactions.setTimeout(WRITE_TIMEOUT_SECONDS);
        this.clock = Objects.requireNonNull(clock);
        this.meters = meters;
    }

    /**
     * 채점을 묶는다.
     *
     * @param feed 채점 스레드의 줄
     * @param forecasts 예보 기록 저장소
     * @param recentRoutes 기동 직후 되감을 노선
     * @param passes 통과 기록 저장소
     * @param labels 정답·미해결 저장소
     */
    public ScoringService scoring(
        ScoringFeed feed,
        ForecastRecordStore forecasts,
        RecentForecastRoutes recentRoutes,
        StopPassStore passes,
        PassLabelStore labels
    ) {
        ScoringReplay replay = new ScoringReplay(forecasts, passes);
        return new ScoringService(feed, scoringStage(), replay, new ScoringWriter(labels, transactions),
            recentRoutes, replay.maxForecastWindow(), clock, meters);
    }

    /** 저장된 정산 근거와 노선 구조로 판정하는 채점 단계를 만든다. 부를 때마다 상태가 빈 새 단계다. */
    public ScoringStage scoringStage() {
        return new ScoringStage(new JdbcSettlementContextProvider(jdbc), new JdbcRouteStructureProvider(jdbc),
            new AlightingAwarePassLabelCalculator(), clock, ScoringStage.DEFAULT_OBSERVATION_LIMIT);
    }

    /**
     * 예보를 묶는다. 노선 상태는 빈 채로 시작하므로 노선마다 첫 배치에서 되감는다.
     *
     * @param lanes 노선별 실행 줄
     * @param events 커밋 뒤 사건을 받을 뒤 단계
     * @param capacities 차량 정원
     * @param records 예보 기록 저장소
     * @param passes 통과 기록 저장소
     */
    public RunForecastsService forecast(
        ForecastLanes lanes,
        ForecastEvents events,
        Sources sources,
        VehicleCapacityCache capacities,
        ForecastRecordStore records,
        StopPassStore passes,
        Settings settings
    ) {
        ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(capacities));
        RouteStateReplayer replayer = new RouteStateReplayer(sources.replays(), stage, settings.replayWindow(),
            settings.replayExtendedWindow());
        ForecastBatchProcessor processor = new ForecastBatchProcessor(stage, replayer, records, passes, transactions,
            events, new RouteStateRegistry(), settings.rushOnly());
        return new RunForecastsService(lanes, processor, sources.batches(), sources.pending(), sources.runtimes(),
            sources.versions(), sources.stops(), new JdbcSettlementContextWriter(jdbc), settings.policy(), clock,
            meters);
    }

    /**
     * 예보가 읽는 조회다.
     *
     * @param replays 되감기 원본
     * @param batches 배치를 착수 시각과 함께 읽는 원본. 되감기와 같은 원본이어야 한다
     * @param pending 처리하지 않은 배치 조회
     * @param runtimes 활성 모델
     * @param versions 활성 노선 판본
     * @param stops 노선 판본의 정류장
     */
    public record Sources(
        ReplaySource replays,
        ObservationBatchReader batches,
        PendingBatches pending,
        ForecastRuntime runtimes,
        RouteVersionQuery versions,
        RouteStopsQuery stops
    ) {
    }

    /**
     * 예보의 조절값이다.
     *
     * @param policy 신선도 창과 한 번에 읽는 배치 수
     * @param replayWindow 다시 뜰 때 처음 되감는 관측 범위
     * @param replayExtendedWindow 되감은 상태가 모자랄 때 한 번 늘려 되감는 범위
     * @param rushOnly 시간 확장 모델이 없을 때 출퇴근 범위 밖 배치를 예보하지 않을지
     */
    public record Settings(
        ForecastPolicy policy,
        Duration replayWindow,
        Duration replayExtendedWindow,
        boolean rushOnly
    ) {
        /** worker가 쓰는 값이다. 되감기 창은 6시간과 12시간이고, 출퇴근 범위 밖은 시간 확장 모델이 있을 때만 예보한다. */
        public static Settings of(ForecastPolicy policy) {
            return new Settings(policy, RouteStateReplayer.DEFAULT_WINDOW, RouteStateReplayer.DEFAULT_EXTENDED_WINDOW,
                true);
        }
    }
}
