package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.application.forecast.ForecastEvents;
import com.gustler.backend.forecasting.application.forecast.ForecastRecordStore;
import com.gustler.backend.forecasting.application.forecast.ForecastStage;
import com.gustler.backend.forecasting.application.forecast.RouteStateReplayer;
import com.gustler.backend.forecasting.application.forecast.RunForecastsService;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예보가 남기는 진단 로그와 지표, 실행 줄이 닫힐 때의 훑기를 실제 DB로 본다. 흐름에는 정원을 모르는 차량과 정원이 모델 범위보다
 * 작은 차량이 하나씩 있다.
 */
@ForecastingIntegrationTest
@Transactional
class ForecastDiagnosticsTest {
    private static final Pattern WITHHELD = Pattern.compile(
        "event=forecast_vehicle_withheld reason=(\\S+) batchId=(\\d+) vehicleId=(\\S+)");
    private static final String LOGGER_PACKAGE = RunForecastsService.class.getPackageName();

    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    ModelDeploymentRepository deployments;
    @Autowired
    ForecastRecordStore records;
    @Autowired
    StopPassStore passes;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LOGGER_PACKAGE);

    @BeforeEach
    void 로그_수집을_시작한다() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void 로그_수집을_끝낸다() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void 정원_때문에_예보에서_뺀_차량을_배치마다_사유와_함께_남기고_제외_지표에_센다() {
        // given
        ForecastHarness harness = harness(20261311L, Duration.ofMinutes(40));
        harness.start();
        harness.fromVerifiedOrigin();
        List<Long> batches = harness.batches();

        // when
        batches.forEach(harness::signal);

        // then
        List<Matcher> withheld = lines("event=forecast_vehicle_withheld ").stream()
            .map(WITHHELD::matcher)
            .filter(Matcher::matches)
            .toList();
        Set<String> batchesOfOneVehicle = withheld.stream()
            .filter(line -> line.group(3).equals(withheld.getFirst().group(3)))
            .map(line -> line.group(2))
            .collect(Collectors.toSet());
        assertSoftly(softly -> {
            softly.assertThat(withheld).extracting(line -> line.group(1)).containsOnly(
                ForecastStage.VEHICLE_CAPACITY_UNKNOWN, ForecastStage.VEHICLE_CAPACITY_OUTSIDE_MODEL_SCOPE);
            softly.assertThat(count(withheld, ForecastStage.VEHICLE_CAPACITY_UNKNOWN)).isPositive()
                .isEqualTo((long) harness.excluded(ForecastStage.VEHICLE_CAPACITY_UNKNOWN));
            softly.assertThat(count(withheld, ForecastStage.VEHICLE_CAPACITY_OUTSIDE_MODEL_SCOPE)).isPositive()
                .isEqualTo((long) harness.excluded(ForecastStage.VEHICLE_CAPACITY_OUTSIDE_MODEL_SCOPE));
            softly.assertThat(withheld).extracting(line -> Long.valueOf(line.group(2))).isSubsetOf(batches);
            softly.assertThat(batchesOfOneVehicle).as("같은 차량이 배치마다 다시 남는다").hasSizeGreaterThan(1);
            softly.assertThat(vehiclesWithRecords(withheld)).as("뺀 차량은 예보 기록이 없다").isZero();
        });
    }

    @Test
    void 노선의_모델_배포는_예측을_처음_저장한_배치에서_한_번만_남긴다() {
        // given
        ForecastHarness harness = harness(20261312L, Duration.ofMinutes(40));
        harness.start();
        harness.fromVerifiedOrigin();
        List<Long> batches = harness.batches();

        // when
        batches.forEach(harness::signal);

        // then
        final long firstForecast = jdbc.sql("""
            SELECT observation_batch_id FROM forecast_run
            WHERE observation_batch_id IN (:batches) AND prediction_count > 0
            ORDER BY confirmed_at, observation_batch_id LIMIT 1
            """).param("batches", batches).query(Long.class).single();
        assertThat(lines("event=forecast_model_used ")).containsExactly(
            "event=forecast_model_used routeId=\"234001739\" routeName=\"%s\" routeVersionId=%d batchId=%d "
                .formatted(harness.stops().routeName(), harness.stops().routeVersionId(), firstForecast)
                + "modelDeploymentId=%d releaseId=\"%s\" featureContractVersion=\"%s\"".formatted(
                    harness.runtime.deploymentId(), harness.runtime.releaseId(),
                    harness.runtime.featureContractVersion()));
    }

    @Test
    void 모델의_기준_정류장이_노선_판본과_다르면_건너뛴_노선을_한_번만_경고로_남긴다() {
        // given
        ForecastHarness harness = harness(20261313L, Duration.ofMinutes(10));
        harness.start();
        harness.breakReference();
        final long batch = harness.batches().getFirst();

        // when
        harness.pickUp(harness.intended(batch));
        harness.signal(batch);
        harness.pickUp(harness.intended(batch).plusSeconds(30));

        // then
        List<ILoggingEvent> skipped = events("event=forecast_route_skipped ");
        assertSoftly(softly -> {
            softly.assertThat(skipped).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                    "event=forecast_route_skipped reason=ROUTE_REFERENCE_MISMATCH routeId=\"234001739\" "
                        + "routeName=\"%s\" routeVersionId=%d modelDeploymentId=%d releaseId=\"%s\"".formatted(
                            harness.stops().routeName(), harness.stops().routeVersionId(),
                            harness.runtime.deploymentId(), harness.runtime.releaseId()));
            });
            softly.assertThat(harness.counter("batches", "reference_mismatch")).isOne();
            softly.assertThat(harness.runOutcome(batch)).isEmpty();
        });
    }

    @Test
    void 활성_모델이_없어진_때와_다시_생긴_때를_한_번씩_남긴다() {
        // given
        ForecastHarness harness = harness(20261314L, Duration.ofMinutes(10));
        harness.start();
        Instant at = harness.intended(harness.batches().getFirst());

        // when
        harness.modelActive(false);
        harness.pickUp(at);
        harness.pickUp(at.plusSeconds(30));
        harness.modelActive(true);
        harness.pickUp(at.plusSeconds(60));
        harness.pickUp(at.plusSeconds(90));

        // then
        assertSoftly(softly -> {
            softly.assertThat(events("event=forecast_model_unavailable")).singleElement()
                .extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
            softly.assertThat(lines("event=forecast_model_available ")).containsExactly(
                "event=forecast_model_available modelDeploymentId=%d releaseId=\"%s\"".formatted(
                    harness.runtime.deploymentId(), harness.runtime.releaseId()));
        });
    }

    @Test
    void 진단_수신자가_실패하면_경고로_남기고_배치는_저장한다() {
        // given
        ForecastHarness harness = harness(20261315L, Duration.ofMinutes(10));
        harness.start();
        harness.fromVerifiedOrigin();
        harness.telemetry(new ForecastTelemetry() {
            @Override
            public void published(
                final long routeId,
                final long routeVersionId,
                Instant observedAt,
                final int predictions
            ) {
                throw new IllegalStateException("수신 실패 흉내");
            }
        });
        final long batch = harness.consecutiveConfirmed(0, 1).getFirst();
        harness.batches().subList(0, harness.batches().indexOf(batch)).forEach(harness::signal);
        logs.list.clear();

        // when
        harness.signal(batch);

        // then
        assertSoftly(softly -> {
            softly.assertThat(events("event=forecast_telemetry_failed ")).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                    "event=forecast_telemetry_failed routeVersionId=%d exceptionType=IllegalStateException"
                        .formatted(harness.stops().routeVersionId()));
            });
            softly.assertThat(harness.runCount(batch)).isOne();
        });
    }

    @Test
    void 실행_줄이_닫히기_시작하면_처리하던_배치_하나만_끝내고_남은_배치는_처리하지_않는다() {
        // given
        ForecastHarness harness = harness(20261316L, Duration.ofMinutes(20));
        harness.start(RouteStateReplayer.DEFAULT_WINDOW, RouteStateReplayer.DEFAULT_EXTENDED_WINDOW,
            new ClosingOnForecast(harness::closeLanes));
        harness.fromVerifiedOrigin();
        List<Long> waiting = harness.batches().subList(0, 5);
        waiting.subList(0, 4).forEach(harness::commit);

        // when
        harness.signal(waiting.getLast());

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.runCount(waiting.getFirst())).isOne();
            softly.assertThat(waiting.subList(1, 5)).allSatisfy(batch -> assertThat(harness.runCount(batch)).isZero());
        });
    }

    @Test
    void 실행_줄이_닫히기_시작한_뒤에는_실패한_배치를_그_자리에서_다시_처리하지_않는다() {
        // given
        ForecastHarness harness = harness(20261317L, Duration.ofMinutes(20));
        harness.start();
        harness.fromVerifiedOrigin();
        final long failing = harness.batches().getFirst();
        harness.failSaves(failing, 1);
        harness.onSaveFailure(harness::closeLanes);

        // when
        harness.signal(failing);

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.counter("batches", "retried")).isZero();
            softly.assertThat(harness.counter("batches", "failed")).isOne();
            softly.assertThat(harness.runCount(failing)).isZero();
        });
    }

    /** 예보 사건을 받는 순간 실행 줄을 닫히기 시작한 것으로 바꾸는 뒤 단계다. */
    private record ClosingOnForecast(Runnable close) implements ForecastEvents {
        @Override
        public void batchSeen(ObservationBatchSeen seen) {
        }

        @Override
        public void forecastPublished(ForecastPublished published) {
            close.run();
        }

        @Override
        public void passesClosed(List<PassClosed> passes) {
        }
    }

    private ForecastHarness harness(final long seed, Duration length) {
        return new ForecastHarness(new ForecastHarness.Beans(jdbc, transactions, deployments, records, passes), seed,
            length);
    }

    private List<String> lines(String prefix) {
        return events(prefix).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<ILoggingEvent> events(String prefix) {
        return List.copyOf(logs.list).stream()
            .filter(event -> event.getFormattedMessage().startsWith(prefix))
            .toList();
    }

    private static long count(List<Matcher> withheld, String reason) {
        return withheld.stream().filter(line -> line.group(1).equals(reason)).count();
    }

    /** 제외 로그에 남은 (배치, 차량)에 예보 기록이 있는 수다. */
    private long vehiclesWithRecords(List<Matcher> withheld) {
        long found = 0;
        for (Matcher line : withheld) {
            found += jdbc.sql("SELECT count(*) FROM forecast_record WHERE observation_batch_id=? AND vehicle_id=?")
                .params(Long.valueOf(line.group(2)), line.group(3)).query(Long.class).single();
        }
        return found;
    }
}
