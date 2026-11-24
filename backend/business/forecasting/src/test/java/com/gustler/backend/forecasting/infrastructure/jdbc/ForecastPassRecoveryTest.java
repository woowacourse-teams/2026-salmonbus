package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.application.forecast.PassLabelStore;
import com.gustler.backend.forecasting.application.forecast.RouteStateReplayer;
import com.gustler.backend.forecasting.application.forecast.ScoringService;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.configuration.ForecastAssembly;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * 되감기가 실패해 예보 단계를 돌리지 못하고 끝난 배치가 닫은 통과를 예보가 다음 되감기에서 되찾는지를 실제 DB와 채점으로 본다.
 * 비교 기준은 같은 흐름을 실패 없이 처리한 결과다. 두 번을 같은 시험 트랜잭션에서 차례로 돌리고, 사이에 예보 기록 테이블의 줄만 지운다.
 */
@ForecastingIntegrationTest
@Transactional
class ForecastPassRecoveryTest {

    private static final List<String> NEW_TABLES = List.of(
            "pass_label",
            "forecast_unresolved",
            "stop_pass",
            "forecast_record",
            "forecast_run"
    );
    private static final Duration RETRY = Duration.ofMinutes(5);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    ModelDeploymentRepository deployments;
    @Autowired
    JdbcForecastRecordStore records;
    @Autowired
    StopPassStore passes;
    @Autowired
    PassLabelStore labels;

    @Test
    void 되감기가_실패해_예보하지_못한_배치가_닫은_통과를_다음_되감기에서_저장하고_기다리던_예측에_연속_실행과_같은_정답이_붙는다() {
        // given
        ForecastHarness harness = new ForecastHarness(
            new ForecastHarness.Beans(
                    jdbc,
                    transactions,
                    deployments,
                    records,
                    passes
            ), 20261310L,
            Duration.ofMinutes(60));
        Run continuous = run(harness, null);
        final long failing = batchClosingAwaitedPassSoonAfter(harness, continuous);
        NEW_TABLES.forEach(table -> jdbc.sql("DELETE FROM " + table + " WHERE route_id=?")
            .param(harness.stream.routeId()).update());
        harness.rewindCollection();

        // when
        Run recovered = run(harness, failing);

        // then
        Set<Long> unforecast = recovered.runOutcomes.entrySet().stream()
            .filter(run -> "INPUT_UNAVAILABLE".equals(run.getValue()))
            .filter(run -> !"INPUT_UNAVAILABLE".equals(continuous.runOutcomes.get(run.getKey())))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        Set<String> passesOfUnforecast = recovered.closureBatches.entrySet().stream()
            .filter(pass -> unforecast.contains(pass.getValue()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        Map<String, Outcome> expectedOutcomes = new HashMap<>(continuous.outcomes);
        expectedOutcomes.values().removeIf(outcome -> unforecast.contains(outcome.batchId()));
        Map<String, String> expectedRecords = new HashMap<>(continuous.records);
        expectedRecords.keySet().removeIf(key -> unforecast.contains(Long.parseLong(key.split(":")[0])));
        final long settledByRecoveredPass = recovered.outcomes.values().stream()
            .filter(outcome -> "SETTLED".equals(outcome.state()))
            .filter(outcome -> passesOfUnforecast.contains(outcome.pass()))
            .count();
        assertSoftly(softly -> {
            softly.assertThat(unforecast).contains(failing).hasSizeGreaterThan(5);
            softly.assertThat(passesOfUnforecast).isNotEmpty();
            softly.assertThat(recovered.passes).isEqualTo(continuous.passes);
            softly.assertThat(recovered.outcomes).isEqualTo(expectedOutcomes);
            softly.assertThat(settledByRecoveredPass).isPositive();
            softly.assertThat(recovered.records).isEqualTo(expectedRecords);
            softly.assertThat(recovered.delivered).doesNotHaveDuplicates().hasSameSizeAs(continuous.delivered);
            softly.assertThat(harness.counter("passes.recovered")).isEqualTo(passesOfUnforecast.size());
            softly.assertThat(harness.counter("replays", "failed")).isOne();
            softly.assertThat(harness.counter("replays", "succeeded")).isEqualTo(2);
        });
    }

    @Test
    void 되감은_뒤_연결_시작을_보지_못한_차량이_닫은_통과는_저장하지_않고_그_수를_지표로_낸다() {
        // given
        ForecastHarness harness = new ForecastHarness(
            new ForecastHarness.Beans(
                    jdbc,
                    transactions,
                    deployments,
                    records,
                    passes
            ), 20261311L,
            Duration.ofMinutes(60));
        List<Long> batches = harness.batches();
        harness.start();
        harness.fromVerifiedOrigin();
        batches.forEach(harness::signal);
        Duration shortWindow = Duration.ofMinutes(5);
        final int restartAt = restartInsideLongChain(harness, shortWindow);
        List<Long> tail = batches.subList(restartAt, batches.size());
        Map<String, String> continuous = passesClosedBy(harness, tail);
        NEW_TABLES.forEach(table -> jdbc.sql("DELETE FROM " + table + " WHERE route_id=?")
            .param(harness.stream.routeId()).update());
        harness.rewindCollection();
        harness.start();
        harness.fromVerifiedOrigin();
        batches.subList(0, restartAt).forEach(harness::signal);

        // when
        harness.start(shortWindow, new ForecastHarness.NoEvents());
        tail.forEach(harness::signal);

        // then
        Map<String, String> restarted = passesClosedBy(harness, tail);
        final double unattributed = harness.counter("passes.unattributed");
        assertSoftly(softly -> {
            softly.assertThat(unattributed).isPositive();
            softly.assertThat(restarted.size()).isLessThan(continuous.size());
            softly.assertThat(restarted.size() + unattributed).isLessThanOrEqualTo(continuous.size());
            softly.assertThat(continuous).containsAllEntriesOf(restarted);
        });
    }

    /**
     * 다시 띄울 배치의 순번을 고른다. 한 차량의 연결이 되감기 창 시작 전부터 이어지고, 그 연결의 통과 하나가 창 안에서 도착해 다시
     * 뜬 뒤에 닫히는 지점이다. 그 통과는 되감은 상태가 연결 시작을 보지 못한 차량의 통과가 된다.
     */
    private int restartInsideLongChain(ForecastHarness harness, Duration window) {
        Instant chainStartedAt = jdbc.sql("""
            SELECT chain.confirmed_at
            FROM stop_pass pass
              JOIN vehicle_observation start ON start.id=pass.chain_start_observation_id
              JOIN forecast_run chain ON chain.observation_batch_id=start.observation_batch_id
            WHERE pass.route_id=? AND pass.arrival_confirmed_at>=chain.confirmed_at+interval '8 minutes'
            ORDER BY pass.closed_at LIMIT 1
            """).param(harness.stream.routeId()).query(OffsetDateTime.class).single().toInstant();
        Instant restartAfter = chainStartedAt.plus(window).plusSeconds(35);
        List<Long> batches = harness.batches();
        for (int index = 0; index < batches.size(); index++) {
            if (harness.intended(batches.get(index)).isAfter(restartAfter)) {
                return index;
            }
        }
        throw new IllegalStateException("다시 띄울 지점이 없다");
    }

    /**
     * 그 배치들이 닫은 통과 줄을 (닫은 관측, 정류장)별로 읽는다. 운행 시작 관측은 뺀다. 되감은 창 안에서 운행 시작을 보지 못한
     * 차량은 창 안 첫 관측이 운행 시작으로 적혀 계속 돈 상태와 다를 수 있다.
     */
    private Map<String, String> passesClosedBy(ForecastHarness harness, List<Long> batches) {
        Map<String, String> rows = new HashMap<>();
        jdbc.sql("""
            SELECT closure_observation_id||':'||stop_order,(to_jsonb(pass)-'trip_start_observation_id')::text
            FROM stop_pass pass WHERE route_id=:route AND closure_batch_id IN (:batches)
            """).param("route", harness.stream.routeId()).param("batches", batches).query(row -> {
                rows.put(row.getString(1), row.getString(2));
            });
        return rows;
    }

    /**
     * 흐름 전체를 예보와 채점으로 처리한다. {@code failing}이 있으면 그 배치에서 노선 상태를 버리고 되감기를 한 번 실패시킨다.
     * 되감기가 성공하고 열 배치 뒤에 노선 상태를 한 번 더 버려, 같은 실행 줄이 든 창을 다시 되감게 한다.
     */
    private Run run(ForecastHarness harness, Long failing) {
        ForecastHarness.RecordedPasses delivered = new ForecastHarness.RecordedPasses();
        ScoringService scoring = scoring(harness);
        harness.start(RouteStateReplayer.DEFAULT_WINDOW, new ForecastHarness.BothEvents(scoring, delivered));
        harness.fromVerifiedOrigin();
        List<Long> batches = harness.batches();
        int dropAt = -1;
        for (int index = 0; index < batches.size(); index++) {
            final long batchId = batches.get(index);
            if (Objects.equals(failing, batchId)) {
                harness.dropState();
                harness.failNextReplay();
            }
            if (index == dropAt) {
                harness.dropState();
            }
            harness.signal(batchId);
            scoring.flush();
            if (dropAt < 0 && harness.counter("replays", "succeeded") == 1) {
                dropAt = index + 10;
            }
        }
        harness.clock.at = harness.clock.at.plus(Duration.ofHours(3));
        scoring.tick();
        scoring.flush();
        return capture(harness, delivered);
    }

    private ScoringService scoring(ForecastHarness harness) {
        return new ForecastAssembly(
                jdbc,
                transactions,
                harness.clock,
                new SimpleMeterRegistry()
        ).scoring(
                task -> {
                    task.run();
                    return true;
                },
                records,
                records,
                passes,
                labels
        );
    }

    /**
     * 되감기를 실패시킬 배치를 고른다. 그 배치부터 다시 되감기까지(착수 시각 기준 5분) 예보하지 못하는 배치 가운데 하나가, 그 앞
     * 배치의 예측이 기다리던 통과를 닫아 정답이 붙은 적이 있어야 한다.
     */
    private long batchClosingAwaitedPassSoonAfter(ForecastHarness harness, Run continuous) {
        List<Long> batches = harness.batches();
        for (int index = 40; index < batches.size() - 30; index++) {
            final long candidate = batches.get(index);
            if (!harness.meetsConfirmation(candidate)) {
                continue;
            }
            Instant retryAt = harness.intended(candidate).plus(RETRY);
            Set<Long> before = new HashSet<>(batches.subList(0, index));
            Set<Long> unforecast = batches.subList(index, batches.size()).stream()
                .filter(batchId -> harness.intended(batchId).isBefore(retryAt))
                .collect(Collectors.toSet());
            final boolean awaited = continuous.outcomes.values().stream()
                .filter(outcome -> "SETTLED".equals(outcome.state()))
                .filter(outcome -> before.contains(outcome.batchId()))
                .anyMatch(outcome -> unforecast.contains(continuous.closureBatches.get(outcome.pass())));
            if (awaited) {
                return candidate;
            }
        }
        throw new IllegalStateException("기다리던 통과를 닫는 배치가 없다");
    }

    private Run capture(ForecastHarness harness, ForecastHarness.RecordedPasses delivered) {
        final long routeId = harness.stream.routeId();
        Map<Long, String> runOutcomes = new HashMap<>();
        jdbc.sql("SELECT observation_batch_id,outcome FROM forecast_run WHERE route_id=?").param(routeId)
            .query(row -> {
                runOutcomes.put(row.getLong(1), row.getString(2));
            });
        Map<String, String> passRows = new HashMap<>();
        Map<String, Long> closureBatches = new HashMap<>();
        jdbc.sql("""
            SELECT closure_observation_id||':'||stop_order,closure_batch_id,to_jsonb(pass)::text
            FROM stop_pass pass WHERE route_id=?
            """).param(routeId).query(row -> {
                passRows.put(row.getString(1), row.getString(3));
                closureBatches.put(row.getString(1), row.getLong(2));
            });
        Map<String, String> recordRows = new HashMap<>();
        jdbc.sql("""
            SELECT observation_batch_id||':'||vehicle_observation_id,to_jsonb(record)::text
            FROM forecast_record record WHERE route_id=?
            """).param(routeId).query(row -> {
                recordRows.put(row.getString(1), row.getString(2));
            });
        Map<String, Outcome> outcomes = new HashMap<>();
        jdbc.sql("""
            SELECT vehicle_observation_id||':'||target_stop_order,observation_batch_id,state,reason,label_value::text,
              closure_observation_id||':'||target_stop_order,previous_last_observation_id,
              previous_closure_observation_id
            FROM forecast_outcome WHERE route_id=?
            """).param(routeId).query(row -> {
                outcomes.put(row.getString(1), new Outcome(
                        row.getLong(2),
                        row.getString(3),
                        row.getString(4),
                        row.getString(5),
                        row.getString(6),
                        (Long) row.getObject(7),
                        (Long) row.getObject(8)
                ));
            });
        return new Run(
                runOutcomes,
                passRows,
                closureBatches,
                recordRows,
                outcomes,
                List.copyOf(delivered.keys)
        );
    }

    /**
     * 한 번 처리한 결과다.
     *
     * @param runOutcomes 배치별 예보 실행 결과
     * @param passes (닫은 관측, 정류장)별 통과 줄 전체
     * @param closureBatches (닫은 관측, 정류장)별 통과를 닫은 배치
     * @param records (배치, 관측)별 버스별 기록 줄 전체
     * @param outcomes (관측, 목표)별 채점 결과
     * @param delivered 뒤 단계가 받은 통과의 (운행 시작 관측, 정류장)
     */
    private record Run(
        Map<Long, String> runOutcomes,
        Map<String, String> passes,
        Map<String, Long> closureBatches,
        Map<String, String> records,
        Map<String, Outcome> outcomes,
        List<String> delivered
    ) {

    }

    /** 예측 하나의 채점 결과다. {@code pass}는 정답을 준 통과의 (닫은 관측, 정류장)이고 정답이 없으면 비어 있다. */
    private record Outcome(
        long batchId,
        String state,
        String reason,
        String labelValue,
        String pass,
        Long previousLastObservationId,
        Long previousClosureObservationId
    ) {

    }
}
