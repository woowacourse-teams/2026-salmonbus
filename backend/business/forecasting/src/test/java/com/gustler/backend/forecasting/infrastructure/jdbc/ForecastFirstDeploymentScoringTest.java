package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.application.forecast.ForecastStage;
import com.gustler.backend.forecasting.application.forecast.PassLabelStore;
import com.gustler.backend.forecasting.application.forecast.RouteStateReplayer;
import com.gustler.backend.forecasting.application.forecast.ScoringService;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.configuration.ForecastAssembly;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.forecast.SourceDates;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새로 뜬 worker가 닫은 통과의 정답이 그 전에 닫힌 통과를 직전 통과로 쓰는지를 실제 DB와 채점으로 본다. 흐름의 앞부분을 먼저 처리된
 * 것으로 두고 뒷부분을 새로 뜬 worker(예보와 채점의 메모리가 모두 빈 상태)로 처리한다. 입력 부족으로 끝난 실행 기록도
 * 통과 복구에 쓰고, 옛 입력 확정 시각만 있는 배치는 복구에 쓰지 않는다. 비교 기준은 같은 흐름을 처음부터 한 worker가 끊지 않고
 * 처리한 결과다. 두 번을 같은 시험 트랜잭션에서 차례로 돌리고, 사이에 예보 기록 테이블의 줄만 지운다.
 */
@ForecastingIntegrationTest
@Transactional
class ForecastFirstDeploymentScoringTest {

    private static final List<String> NEW_TABLES = List.of(
            "pass_label",
            "forecast_unresolved",
            "stop_pass",
            "forecast_record"
    );
    private static final Duration LENGTH = Duration.ofHours(2);
    /** 흐름이 시작하는 한국 날짜의 다음 날 00시다. */
    private static final Instant MIDNIGHT = SourceDates.of(ForecastHarness.EVENING).plusDays(1)
        .atStartOfDay(SourceDates.KOREA).toInstant();
    /** 한국 날짜가 바뀐 뒤까지 이어지는 흐름의 길이다. */
    private static final Duration OVER_MIDNIGHT = Duration.between(ForecastHarness.EVENING, MIDNIGHT)
        .plus(Duration.ofHours(1));

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
    void 입력_부족으로_끝난_예보_실행_기록을_복구하면_그_뒤의_예측에_연속_실행과_같은_정답이_붙는다() {
        // given
        ForecastHarness harness = harness(20261401L, LENGTH);
        List<Long> batches = harness.batches();
        List<Long> before = batches.subList(0, batches.size() / 2);
        List<Long> after = batches.subList(batches.size() / 2, batches.size());
        Run continuous = continuous(harness);
        reset(harness, before);
        markInputUnavailable(before);

        // when
        ScoringService scoring = startWorker(harness, harness.intended(after.getFirst()));
        process(harness, scoring, after);
        closeAll(harness, scoring);

        // then
        Run deployed = capture(harness);
        Map<String, Outcome> expected = continuous.outcomesOf(after);
        Set<String> settledAfterEarlierPass = settledWithPreviousPassClosedBy(continuous, expected, before);
        assertSoftly(softly -> {
            softly.assertThat(excluded(harness)).as("되감은 직후라 예보에서 뺀 관측").isZero();
            softly.assertThat(deployed.records).as("배포 뒤 배치의 예측값").isEqualTo(continuous.recordsOf(after));
            softly.assertThat(deployed.passes).as("저장된 통과").isEqualTo(continuous.passes);
            softly.assertThat(settledAfterEarlierPass)
                .as("연속 실행에서 직전 통과가 배포 전에 닫혔고 정답이 붙은 예측").isNotEmpty();
            softly.assertThat(changes(expected, deployed.outcomes))
                .as("연속 실행과 채점 결과가 다른 예측 수(연속 실행 -> 처음 뜬 worker)").isEmpty();
            softly.assertThat(deployed.outcomes.keySet()).isEqualTo(expected.keySet());
        });
    }

    @Test
    void 새_worker가_처리해_예보_기록을_남긴_배치_뒤에_다시_떠도_예측마다_연속_실행과_같은_정답이_붙는다() {
        // given
        ForecastHarness harness = harness(20261401L, LENGTH);
        List<Long> batches = harness.batches();
        List<Long> before = batches.subList(0, batches.size() / 2);
        List<Long> after = batches.subList(batches.size() / 2, batches.size());
        Run continuous = continuous(harness);
        reset(harness, List.of());
        ScoringService first = startWorker(harness, harness.intended(before.getFirst()));
        harness.fromVerifiedOrigin();
        process(harness, first, before);

        // when
        ScoringService second = startWorker(harness, harness.intended(after.getFirst()));
        process(harness, second, after);
        closeAll(harness, second);

        // then
        Run restarted = capture(harness);
        Set<String> settledAfterEarlierPass = settledWithPreviousPassClosedBy(continuous,
            continuous.outcomesOf(after), before);
        assertSoftly(softly -> {
            softly.assertThat(excluded(harness)).as("되감은 직후라 예보에서 뺀 관측").isZero();
            softly.assertThat(restarted.records).isEqualTo(continuous.records);
            softly.assertThat(settledAfterEarlierPass)
                .as("연속 실행에서 직전 통과가 다시 뜨기 전에 닫혔고 정답이 붙은 예측").isNotEmpty();
            softly.assertThat(changes(continuous.outcomes, restarted.outcomes))
                .as("연속 실행과 채점 결과가 다른 예측 수(연속 실행 -> 다시 뜬 worker)").isEmpty();
            softly.assertThat(restarted.outcomes.keySet()).isEqualTo(continuous.outcomes.keySet());
        });
    }

    @Test
    void 옛_입력_확정_시각만_있는_배치가_닫은_통과는_날짜와_관계없이_복구하지_않는다() {
        // given
        ForecastHarness harness = harness(20261402L, OVER_MIDNIGHT);
        Instant deployedAt = MIDNIGHT.plus(Duration.ofMinutes(30));
        List<Long> batches = harness.batches();
        List<Long> before = batches.stream().filter(batchId -> harness.intended(batchId).isBefore(deployedAt)).toList();
        List<Long> after = batches.subList(before.size(), before.size() + 10);
        confirmByPreviousRelease(harness, before);
        harness.start();

        // when
        after.forEach(harness::signal);

        // then
        Map<LocalDate, Long> recovered = new HashMap<>();
        jdbc.sql("""
            SELECT source_date,count(*) FROM stop_pass
            WHERE route_id=:route AND closure_batch_id IN (:batches) GROUP BY source_date
            """).param("route", harness.stream.routeId()).param("batches", before).query(row -> {
                recovered.put(row.getObject(1, LocalDate.class), row.getLong(2));
            });
        final long beforeMidnight = before.stream()
            .filter(batchId -> harness.receivedAt(batchId).isBefore(MIDNIGHT))
            .count();
        assertSoftly(softly -> {
            softly.assertThat(beforeMidnight).as("전날 처리한 배치 수").isGreaterThan(500);
            softly.assertThat(recovered).isEmpty();
            softly.assertThat(harness.counter("passes.recovered")).isZero();
            softly.assertThat(harness.counter("replays", "succeeded")).isOne();
        });
    }

    private ForecastHarness harness(final long seed, Duration length) {
        return new ForecastHarness(new ForecastHarness.Beans(
                jdbc,
                transactions,
                deployments,
                records,
                passes
        ), seed,
            length);
    }

    /** 흐름 전체를 한 worker가 검증된 출발 지점에서 끊지 않고 처리한 결과다. */
    private Run continuous(ForecastHarness harness) {
        List<Long> batches = harness.batches();
        ScoringService scoring = startWorker(harness, harness.intended(batches.getFirst()));
        harness.fromVerifiedOrigin();
        process(harness, scoring, batches);
        closeAll(harness, scoring);
        return capture(harness);
    }

    /** 예측과 통과를 지우되, 복구할 실행 기록의 시각과 수집 사실은 실제 처리 결과를 보존한다. */
    private void reset(ForecastHarness harness, List<Long> retainedRuns) {
        NEW_TABLES.forEach(table -> jdbc.sql("DELETE FROM " + table + " WHERE route_id=?")
            .param(harness.stream.routeId()).update());
        harness.rewindCollection();

        if (retainedRuns.isEmpty()) {
            jdbc.sql("DELETE FROM forecast_run WHERE route_id=?").param(harness.stream.routeId()).update();
            return;
        }
        jdbc.sql("DELETE FROM forecast_run WHERE route_id=:route AND observation_batch_id NOT IN (:retained)")
            .param("route", harness.stream.routeId()).param("retained", retainedRuns).update();
    }

    /** 예보를 만들 수 있었던 확정 실행만 입력 부족으로 바꾸며, 늦음과 빈 응답의 의미는 유지한다. */
    private void markInputUnavailable(List<Long> batches) {
        jdbc.sql("""
            UPDATE forecast_run r SET outcome='INPUT_UNAVAILABLE',prediction_count=0,model_deployment_id=NULL
            FROM observation_batch b
            WHERE r.observation_batch_id=b.id AND r.observation_batch_id IN (:batches)
              AND b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS' AND b.stored_rows>0
              AND r.confirmed_at BETWEEN b.response_received_at AND b.response_received_at+interval '90 seconds'
              AND r.outcome IN ('PUBLISHED','OUT_OF_SCOPE','EMPTY')
            """).param("batches", batches).update();
    }

    /**
     * 배치를 이전 배포본이 처리한 것으로 만든다. 이전 배포본은 예보 실행 줄을 쓰지 않고 배치에 입력 확정 시각만 적었다. 현재 worker는 이 시각을
     * 확정 이력의 근거로 사용하지 않는다.
     */
    private void confirmByPreviousRelease(ForecastHarness harness, List<Long> batches) {
        for (final long batchId : batches) {
            jdbc.sql("UPDATE observation_batch SET input_confirmed_at=? WHERE id=?")
                .params(harness.intended(batchId).atOffset(ZoneOffset.UTC), batchId).update();
        }
    }

    /**
     * 예보와 채점을 새로 띄운다. 둘 다 메모리가 빈 채로 시작하고, 채점은 기동 직후의 되감기를 {@code at} 시각에 돌린다.
     */
    private ScoringService startWorker(ForecastHarness harness, Instant at) {
        ScoringService scoring = new ForecastAssembly(
                jdbc,
                transactions,
                harness.clock,
                new SimpleMeterRegistry()
        )
            .scoring(
                    task -> {
                        task.run();
                        return true;
                    },
                    records,
                    records,
                    passes,
                    labels
            );
        harness.start(RouteStateReplayer.DEFAULT_WINDOW, scoring);
        harness.clock.at = at;
        scoring.start();
        return scoring;
    }

    private void process(ForecastHarness harness, ScoringService scoring, List<Long> batches) {
        for (final long batchId : batches) {
            harness.signal(batchId);
            scoring.flush();
        }
    }

    /** 마지막 배치 3시간 뒤의 시계로 주기 작업을 돌려 기다리던 예측을 모두 마감한다. */
    private void closeAll(ForecastHarness harness, ScoringService scoring) {
        harness.clock.at = harness.clock.at.plus(Duration.ofHours(3));
        scoring.tick();
        scoring.flush();
    }

    private double excluded(ForecastHarness harness) {
        return harness.excluded(ForecastStage.TRIP_START_UNKNOWN)
            + harness.excluded(ForecastStage.REPLAY_HISTORY_INCOMPLETE);
    }

    /** 정답이 붙었고 그 정답의 직전 통과를 {@code batches} 가운데 하나가 닫은 예측이다. */
    private Set<String> settledWithPreviousPassClosedBy(
        Run run,
        Map<String, Outcome> outcomes,
        List<Long> batches
    ) {
        Set<Long> closing = new HashSet<>(batches);
        return outcomes.entrySet().stream()
            .filter(entry -> "SETTLED".equals(entry.getValue().state()))
            .filter(entry -> entry.getValue().previousClosureObservationId() != null)
            .filter(entry -> closing.contains(run.closureBatches.get(entry.getValue().previousClosureObservationId())))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    /** 두 결과에서 채점 결과가 다른 예측을 "기대한 판정 -> 실제 판정"별로 센다. 한쪽에만 있는 예측은 없음으로 적는다. */
    private static Map<String, Long> changes(
        Map<String, Outcome> expected,
        Map<String, Outcome> actual
    ) {
        Set<String> keys = new HashSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        Map<String, Long> changes = new TreeMap<>();
        for (String key : keys) {
            Outcome wanted = expected.get(key);
            Outcome found = actual.get(key);
            if (wanted != null && wanted.equals(found)) {
                continue;
            }
            changes.merge(verdict(wanted) + " -> " + verdict(found), 1L, Long::sum);
        }
        return changes;
    }

    private static String verdict(Outcome outcome) {
        if (outcome == null) {
            return "없음";
        }
        return outcome.verdict();
    }

    private Run capture(ForecastHarness harness) {
        final long routeId = harness.stream.routeId();
        Map<Long, Long> closureBatches = new HashMap<>();
        Map<String, String> passRows = new HashMap<>();
        jdbc.sql("""
            SELECT closure_observation_id,closure_batch_id,stop_order,to_jsonb(pass)::text
            FROM stop_pass pass WHERE route_id=?
            """).param(routeId).query(row -> {
                closureBatches.put(row.getLong(1), row.getLong(2));
                passRows.put(row.getLong(1) + ":" + row.getInt(3), row.getString(4));
            });
        Map<String, Long> recordBatches = new HashMap<>();
        Map<String, String> recordRows = new HashMap<>();
        jdbc.sql("""
            SELECT observation_batch_id||':'||vehicle_observation_id,observation_batch_id,to_jsonb(record)::text
            FROM forecast_record record WHERE route_id=?
            """).param(routeId).query(row -> {
                recordBatches.put(row.getString(1), row.getLong(2));
                recordRows.put(row.getString(1), row.getString(3));
            });
        Map<String, Outcome> outcomes = new HashMap<>();
        jdbc.sql("""
            SELECT vehicle_observation_id||':'||target_stop_order,observation_batch_id,state,reason,label_value::text,
              closure_observation_id,previous_last_observation_id,previous_closure_observation_id
            FROM forecast_outcome WHERE route_id=?
            """).param(routeId).query(row -> {
                outcomes.put(row.getString(1), new Outcome(
                        row.getLong(2),
                        row.getString(3),
                        row.getString(4),
                        row.getString(5),
                        (Long) row.getObject(6),
                        (Long) row.getObject(7),
                        (Long) row.getObject(8)
                ));
            });
        return new Run(
                closureBatches,
                passRows,
                recordBatches,
                recordRows,
                outcomes
        );
    }

    /**
     * 한 번 처리한 결과다.
     *
     * @param closureBatches 통과를 닫은 관측별 그 통과를 닫은 배치
     * @param passes (닫은 관측, 정류장)별 통과 줄 전체
     * @param recordBatches (배치, 관측)별 배치
     * @param records (배치, 관측)별 버스별 기록 줄 전체
     * @param outcomes (관측, 목표)별 채점 결과
     */
    private record Run(
        Map<Long, Long> closureBatches,
        Map<String, String> passes,
        Map<String, Long> recordBatches,
        Map<String, String> records,
        Map<String, Outcome> outcomes
    ) {

        /** 그 배치들이 낸 예측의 채점 결과만 남긴다. */
        Map<String, Outcome> outcomesOf(List<Long> batches) {
            Set<Long> wanted = new HashSet<>(batches);
            Map<String, Outcome> selected = new HashMap<>(outcomes);
            selected.values().removeIf(outcome -> !wanted.contains(outcome.batchId()));
            return selected;
        }

        /** 그 배치들의 버스별 기록 줄만 남긴다. */
        Map<String, String> recordsOf(List<Long> batches) {
            Set<Long> wanted = new HashSet<>(batches);
            Map<String, String> selected = new HashMap<>(records);
            selected.keySet().removeIf(key -> !wanted.contains(recordBatches.get(key)));
            return selected;
        }
    }

    /**
     * 예측 하나의 채점 결과다.
     *
     * @param closureObservationId 정답을 준 통과를 닫은 관측. 정답이 없으면 비어 있다
     */
    private record Outcome(
        long batchId,
        String state,
        String reason,
        String labelValue,
        Long closureObservationId,
        Long previousLastObservationId,
        Long previousClosureObservationId
    ) {

        /** 상태와 사유다. */
        String verdict() {
            if (reason == null) {
                return state;
            }
            return state + " " + reason;
        }
    }
}
