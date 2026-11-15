package com.gustler.backend.forecasting.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.application.pipeline.ForecastBatch;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.ForecastStageResult;
import com.gustler.backend.forecasting.application.pipeline.RouteForecastState;
import com.gustler.backend.forecasting.application.pipeline.RouteStateReplayer;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePredictionAssembler;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * 본선({@code forecast.pipeline=v2})이 착수 시각과 이력의 순서를 지키는지를 실제 DB로 본다.
 * 착수 시각은 메모리의 노선 상태와 다시 뜬 뒤 DB에서 되감은 상태가 같은 값을 써야 하고, 한 노선의 이력에는 배치가 수신 순서대로만
 * 들어가야 한다.
 */
@ForecastingIntegrationTest
@Transactional
class ForecastPipelineOrderTest {
    private static final List<String> FORECAST_OUTCOMES = List.of("PUBLISHED", "EMPTY");

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

    @Test
    void 나노초_자리가_있는_시계로_처리해도_되감은_상태와_그_뒤의_입력과_예측이_계속_돈_상태와_비트까지_같다() {
        // given
        MainlineHarness harness = harness(20261301L, Duration.ofMinutes(150));
        harness.start();
        harness.fromVerifiedOrigin();
        List<Long> batches = harness.batches();
        final int split = batches.size() * 2 / 3;
        batches.subList(0, split).forEach(batchId -> harness.signal(batchId, withNanos(harness, batchId)));
        RouteForecastState atSplit = harness.state().orElseThrow();
        RouteForecastState.Cursor cursor = atSplit.cursor().orElseThrow();

        // when
        RouteStateReplayer.Result replayed = new RouteStateReplayer(harness.source, harness.stage,
            Duration.ofHours(6)).replay(harness.statistics(), cursor.confirmedAt(), cursor.observationBatchId());

        // then
        assertSoftly(softly -> {
            softly.assertThat(cursor.confirmedAt()).isEqualTo(harness.runConfirmedAt(cursor.observationBatchId()));
            softly.assertThat(replayed.state().cursor()).contains(cursor);
            softly.assertThat(replayed.state().observations()).isEqualTo(atSplit.observations());
            softly.assertThat(replayed.state().events()).isEqualTo(atSplit.events());
            softly.assertThat(replayed.state().prefix()).isEqualTo(atSplit.prefix());
        });

        // when
        AlightingAwareRouteStatistics statistics = harness.statistics();
        AlightingAwarePredictionAssembler assembler = new AlightingAwarePredictionAssembler(harness.model(),
            statistics, harness.stops());
        RouteForecastState continuous = atSplit;
        RouteForecastState resumed = replayed.state();
        int inputs = 0;
        int predictions = 0;
        for (final long batchId : batches.subList(split, batches.size())) {
            harness.signal(batchId, withNanos(harness, batchId));
            ForecastBatch batch = harness.source.readBatch(batchId).orElseThrow().batch();
            ForecastStageResult expected = harness.stage.advance(continuous, statistics,
                ObservationStreamFixture.SOURCE_ID, batch);
            ForecastStageResult actual = harness.stage.advance(resumed, statistics,
                ObservationStreamFixture.SOURCE_ID, batch);
            assertThat(inputBits(actual.snapshots(), statistics)).as("배치 %d의 입력", batchId)
                .isEqualTo(inputBits(expected.snapshots(), statistics));
            Map<String, List<Long>> predicted = predictionBits(assembler, actual.snapshots());
            assertThat(predicted).as("배치 %d의 예측", batchId).isEqualTo(storedPredictionBits(batchId));
            inputs += actual.snapshots().size();
            predictions += predicted.size();
            continuous = expected.state();
            resumed = actual.state();
        }

        // then
        final int comparedInputs = inputs;
        final int comparedPredictions = predictions;
        assertSoftly(softly -> {
            softly.assertThat(comparedInputs).isGreaterThan(200);
            softly.assertThat(comparedPredictions).isGreaterThan(500);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void 저장이_실패한_배치는_다시_처리해_뒤_배치보다_먼저_이력에_들어간다(final int failures) {
        // given
        MainlineHarness harness = harness(20261302L, Duration.ofMinutes(40));
        harness.start();
        harness.fromVerifiedOrigin();
        List<Long> pair = harness.consecutiveConfirmed(30, 2);
        final long failing = pair.get(0);
        final long next = pair.get(1);
        processBefore(harness, failing);
        harness.failSaves(failing, failures);

        // when
        harness.signal(failing);
        harness.signal(next);
        harness.signal(failing, harness.intended(next).plusSeconds(10));

        // then
        RouteForecastState state = harness.state().orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(harness.runOutcome(failing)).get().isIn(FORECAST_OUTCOMES);
            softly.assertThat(harness.runOutcome(next)).get().isIn(FORECAST_OUTCOMES);
            softly.assertThat(harness.runConfirmedAt(failing)).isBeforeOrEqualTo(harness.runConfirmedAt(next));
            softly.assertThat(rankOf(state, next)).isEqualTo(OptionalLong.of(rankOf(state, failing).orElseThrow() + 1));
            softly.assertThat(inversions(harness)).isZero();
            softly.assertThat(harness.counter("batches", "retried")).isOne();
            softly.assertThat(harness.counter("batches", "failed")).isEqualTo(failures - 1);
            softly.assertThat(harness.counter("batches", "held")).isZero();
            softly.assertThat(harness.counter("batches", "already_processed")).isOne();
        });
    }

    @Test
    void 한_배치가_계속_실패하면_뒤_배치는_그_배치가_늦음이_될_때까지만_기다렸다가_수신_순서대로_이력에_들어간다() {
        // given
        MainlineHarness harness = harness(20261303L, Duration.ofMinutes(40));
        harness.start();
        harness.fromVerifiedOrigin();
        List<Long> run = harness.consecutiveConfirmed(30, 4);
        final long failing = run.get(0);
        List<Long> followers = run.subList(1, 4);
        processBefore(harness, failing);
        harness.failSaves(failing, Integer.MAX_VALUE);

        // when
        harness.signal(failing, harness.receivedAt(failing).plusSeconds(2));
        harness.signal(followers.get(0), harness.receivedAt(followers.get(0)).plusSeconds(2));
        harness.signal(followers.get(1), harness.receivedAt(followers.get(1)).plusSeconds(2));

        // then
        assertSoftly(softly -> {
            for (final long batchId : run.subList(0, 3)) {
                softly.assertThat(harness.runOutcome(batchId)).as("배치 %d", batchId).isEmpty();
            }
            softly.assertThat(harness.counter("batches", "held")).isEqualTo(2);
            softly.assertThat(harness.counter("batches", "retried")).isOne();
            softly.assertThat(harness.counter("batches", "failed")).isEqualTo(3);
        });

        // when
        Instant released = harness.receivedAt(followers.get(2)).plusSeconds(3);
        harness.signal(followers.get(2), released);

        // then
        RouteForecastState state = harness.state().orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(harness.runOutcome(failing)).isEmpty();
            for (final long batchId : followers) {
                softly.assertThat(harness.runOutcome(batchId)).as("배치 %d", batchId).get().isIn(FORECAST_OUTCOMES);
                softly.assertThat(harness.runConfirmedAt(batchId)).isEqualTo(released);
            }
            softly.assertThat(state.cursor().orElseThrow().observationBatchId()).isEqualTo(followers.get(2));
            softly.assertThat(rankOf(state, failing)).isEmpty();
            softly.assertThat(rankOf(state, followers.get(1)))
                .isEqualTo(OptionalLong.of(rankOf(state, followers.get(0)).orElseThrow() + 1));
            softly.assertThat(rankOf(state, followers.get(2)))
                .isEqualTo(OptionalLong.of(rankOf(state, followers.get(1)).orElseThrow() + 1));
            softly.assertThat(inversions(harness)).isZero();
        });

        // when
        harness.failSaves(failing, 0);
        harness.signal(failing, released.plusSeconds(2));

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.runOutcome(failing)).contains("LATE");
            softly.assertThat(harness.state().orElseThrow().cursor()).isEqualTo(state.cursor());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 뒤_배치가_먼저_이력에_들어갔으면_앞_배치는_이력에_넣지_않고_수신_뒤_90초가_지나면_늦음으로_닫는다(final boolean restarted) {
        // given
        MainlineHarness harness = harness(20261304L, Duration.ofMinutes(40));
        harness.start();
        harness.fromVerifiedOrigin();
        List<Long> pair = harness.consecutiveConfirmed(30, 2);
        final long earlier = pair.get(0);
        final long later = pair.get(1);
        processBefore(harness, earlier);
        harness.start();
        harness.signal(later, harness.receivedAt(later).plusSeconds(2));
        if (restarted) {
            harness.start();
        }

        // when
        harness.signal(earlier, harness.receivedAt(later).plusSeconds(4));

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.runOutcome(earlier)).isEmpty();
            softly.assertThat(harness.counter("batches", "out_of_order")).isOne();
        });

        // when
        harness.signal(earlier, harness.receivedAt(earlier).plusSeconds(91));

        // then
        RouteStateReplayer.Result replayed = new RouteStateReplayer(harness.source, harness.stage,
            Duration.ofHours(6)).replay(harness.statistics(), harness.runConfirmedAt(later), later);
        assertSoftly(softly -> {
            softly.assertThat(harness.runOutcome(earlier)).contains("LATE");
            softly.assertThat(rankOf(replayed.state(), earlier)).isEmpty();
            softly.assertThat(replayed.state().cursor().orElseThrow().observationBatchId()).isEqualTo(later);
            softly.assertThat(inversions(harness)).isZero();
        });
    }

    private MainlineHarness harness(final long seed, Duration length) {
        return new MainlineHarness(new MainlineHarness.Beans(jdbc, transactions, deployments, records, passes), seed,
            length);
    }

    private static void processBefore(MainlineHarness harness, final long batchId) {
        List<Long> batches = harness.batches();
        batches.subList(0, batches.indexOf(batchId)).forEach(harness::signal);
    }

    /** 저장 자릿수 아래에 1~998나노초를 붙인 시각이다. 절반쯤은 반올림하면 다음 마이크로초가 된다. */
    private static Instant withNanos(MainlineHarness harness, final long batchId) {
        return harness.intended(batchId).plusNanos(1 + Math.floorMod(batchId * 37, 998));
    }

    /** 이력 안에서 그 배치가 받은 순위다. 이력에 없으면 비어 있다. */
    private static OptionalLong rankOf(RouteForecastState state, final long batchId) {
        return state.observations().stream()
            .filter(observation -> observation.batchId() == batchId)
            .mapToLong(AlightingAwareObservation::batchRank)
            .findFirst();
    }

    /** 이력에 들어가는 실행 줄을 착수 순서로 놓았을 때 수신 시각이 앞 줄보다 이른 줄의 수다. */
    private long inversions(MainlineHarness harness) {
        return jdbc.sql("""
            SELECT count(*) FROM (
              SELECT r.received_at,
                lag(r.received_at) OVER (ORDER BY r.confirmed_at, r.observation_batch_id) AS previous_received_at
              FROM forecast_run r
              WHERE r.route_id=? AND r.confirmed_at<=r.received_at+interval '90 seconds'
            ) ordered WHERE previous_received_at>received_at
            """).param(harness.stream.routeId()).query(Long.class).single();
    }

    private static Map<Long, List<String>> inputBits(
        List<AlightingAwareFeatureSnapshot> snapshots,
        AlightingAwareRouteStatistics statistics
    ) {
        Map<Long, List<String>> bits = new HashMap<>();
        for (AlightingAwareFeatureSnapshot snapshot : snapshots) {
            List<String> targets = new ArrayList<>();
            for (AlightingAwareRouteStatistics.Stop stop : statistics.stops()) {
                targets.add(inputBits(snapshot, stop.order()));
            }
            bits.put(snapshot.vehicleObservationId(), targets);
        }
        return bits;
    }

    private static String inputBits(AlightingAwareFeatureSnapshot snapshot, final int target) {
        try {
            AlightingAwareFeatureVector vector = snapshot.forTarget(target);
            return Arrays.toString(Arrays.stream(vector.seat88()).mapToLong(Double::doubleToRawLongBits).toArray())
                + Arrays.toString(Arrays.stream(vector.probability80()).mapToLong(Double::doubleToRawLongBits)
                    .toArray());
        } catch (IllegalArgumentException unsupported) {
            return "-";
        }
    }

    /** 본선이 버스별 기록을 만드는 것과 같은 규칙으로 (관측, 목표)마다 기대 좌석과 만석 확률의 비트를 만든다. */
    private static Map<String, List<Long>> predictionBits(
        AlightingAwarePredictionAssembler assembler,
        List<AlightingAwareFeatureSnapshot> snapshots
    ) {
        Map<String, List<Long>> bits = new HashMap<>();
        for (AlightingAwareFeatureSnapshot snapshot : snapshots) {
            Optional<AlightingAwarePredictionAssembler.VehiclePrediction> prediction = assembler.predict(snapshot);
            if (prediction.isEmpty()) {
                continue;
            }
            for (AlightingAwarePredictionAssembler.TargetPrediction target : prediction.get().targets()) {
                bits.put(snapshot.vehicleObservationId() + ":" + target.target().stopOrder(),
                    List.of(Double.doubleToLongBits(target.result().expectedSeats()),
                        Double.doubleToLongBits(target.result().fullChance())));
            }
        }
        return bits;
    }

    private Map<String, List<Long>> storedPredictionBits(final long batchId) {
        Map<String, List<Long>> bits = new HashMap<>();
        jdbc.sql("""
            SELECT r.vehicle_observation_id,t.target,t.seats,t.probability FROM forecast_record r,
              unnest(r.target_stop_orders,r.expected_seats,r.full_probabilities) AS t(target,seats,probability)
            WHERE r.observation_batch_id=?
            """).param(batchId).query(row -> {
                bits.put(row.getLong(1) + ":" + row.getInt(2),
                    List.of(Double.doubleToLongBits(row.getDouble(3)), Double.doubleToLongBits(row.getDouble(4))));
            });
        return bits;
    }
}
