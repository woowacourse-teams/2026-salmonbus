package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.forecast.ForecastBatchEvents;
import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.application.forecast.PassLabel;
import com.gustler.backend.forecasting.application.forecast.PassLabelCalculator;
import com.gustler.backend.forecasting.application.forecast.PassLabelStore;
import com.gustler.backend.forecasting.application.forecast.ScoringReasons;
import com.gustler.backend.forecasting.application.forecast.ScoringReplay;
import com.gustler.backend.forecasting.application.forecast.ScoringResult;
import com.gustler.backend.forecasting.application.forecast.ScoringService;
import com.gustler.backend.forecasting.application.forecast.ScoringStage;
import com.gustler.backend.forecasting.application.forecast.ScoringWriter;
import com.gustler.backend.forecasting.application.forecast.UnresolvedForecast;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 정산이 이벤트를 버리거나 처리 중 예외가 나거나 다시 떠도, 저장된 기록을 복구해 연속 실행과 같은 결과 집합을 쓰는지 본다.
 * 줄은 넣는 즉시 같은 스레드에서 실행한다. 저장소는 메모리에 두고, 예보 경로처럼 배치의 실행 기록(수집 사실 포함)과 통과를 먼저
 * 저장한 뒤에 그 배치의 이벤트를 넘긴다.
 */
class ScoringServiceTest {

    private static final int LIMIT = 150_000;
    private static final Instant START = Instant.parse("2026-01-04T21:00:00Z");

    @Test
    void 한_배치의_정산_입력은_큐에_한번만_등록한다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                97,
                START,
                20,
                7,
                0,
                true
        );
        Harness harness = new Harness(scenario, scenario.stage(LIMIT));

        // when
        harness.deliverFirstBatch();

        // then
        assertThat(harness.submitted).isOne();
    }

    @Test
    void 배치_등록을_거절하면_전체_입력을_누락으로_세고_다음_배치에서_복구한다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                97,
                START,
                120,
                7,
                0,
                true
        );
        Harness harness = new Harness(scenario, scenario.stage(LIMIT));
        harness.reject = index -> index == 0;

        // when
        final int next = harness.deliverFirstBatch();

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.submitted).isOne();
            softly.assertThat(harness.counter("dropped")).isEqualTo(2);
        });

        // when
        harness.reject = index -> false;
        harness.deliver(next, harness.events.size());

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.replays("dropped")).isOne();
            softly.assertThat(harness.stored()).isEqualTo(continuous(scenario)).isNotEmpty();
        });
    }

    @Test
    void 줄이_넘쳐_버린_사건이_있어도_그_노선을_되감아_연속_실행과_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                97,
                START,
                500,
                7,
                0,
                true
        );
        Harness harness = new Harness(scenario, scenario.stage(LIMIT));
        int[] passes = passIndexes(harness.events);
        harness.reject = index -> index == passes[passes.length / 3] || index == passes[passes.length / 2] + 1;

        // when
        harness.deliverAll();

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.counter("dropped")).isEqualTo(2);
            softly.assertThat(harness.replays("dropped")).isEqualTo(2);
            softly.assertThat(harness.stored()).isEqualTo(continuous(scenario)).isNotEmpty();
        });
    }

    @Test
    void 처리_중_예외가_나면_노선_상태를_버리고_다음_사건에서_되감아_연속_실행과_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                101,
                START,
                500,
                7,
                0,
                true
        );
        AtomicInteger calls = new AtomicInteger();
        PassLabelCalculator real = new AlightingAwarePassLabelCalculator();
        PassLabelCalculator failingOnce = (pass, previous, evidence) -> {
            if (calls.incrementAndGet() == 40) {
                throw new IllegalStateException("계산 실패 흉내");
            }
            return real.calculate(pass, previous, evidence);
        };
        Harness harness = new Harness(
            scenario,
            scenario.stage(LIMIT, failingOnce)
        );

        // when
        harness.deliverAll();

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.counter("failures")).isOne();
            softly.assertThat(harness.replays("not_ready")).isEqualTo(2);
            softly.assertThat(harness.stored()).isEqualTo(continuous(scenario)).isNotEmpty();
        });
    }

    @Test
    void 다시_뜨면_최근_예보가_있는_노선을_되감아_기다리던_예측을_다시_만들고_이어_간다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                103,
                START,
                500,
                7,
                0,
                true
        );
        Harness before = new Harness(scenario, scenario.stage(LIMIT));
        final int cut = before.events.size() / 2;
        before.deliver(0, cut);
        Harness after = before.restarted(scenario.stage(LIMIT));

        // when
        final boolean started = after.service.start();
        final int waitingAfterStart = after.stage.waitingCount();
        after.deliver(cut, after.events.size());

        // then
        Map<String, Object> merged = new HashMap<>(before.stored());
        after.stored().forEach(merged::putIfAbsent);
        assertSoftly(softly -> {
            softly.assertThat(started).isTrue();
            softly.assertThat(waitingAfterStart).isPositive();
            softly.assertThat(after.replays("startup")).isOne();
            softly.assertThat(after.replays("not_ready")).isZero();
            softly.assertThat(merged).isEqualTo(continuous(scenario));
        });
    }

    @Test
    void 쓰지_못한_결과를_남기고_다시_떠서_되감아도_닫힌_예측은_그_줄을_저장한_프로세스가_한_번만_알린다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                149,
                START,
                500,
                7,
                0,
                true
        );
        Harness before = new Harness(scenario, scenario.stage(LIMIT));
        final int cut = cutLeavingUnwrittenResults(scenario);
        before.deliverUntilCrash(cut);
        Harness after = before.restartedOnSameStore(scenario.stage(LIMIT));

        // when
        after.service.start();
        after.service.flush();
        final int reportedAfterReplay = after.closed.size();
        after.deliver(cut, after.events.size());

        // then
        ScoringStage continuous = scenario.stage(LIMIT);
        List<Object> events = scenario.events();
        ScoringResult all = ScoringScenario.feed(continuous, events).plus(continuous.expire(end(events)));
        assertSoftly(softly -> {
            softly.assertThat(before.closed).as("내려가기 전에 저장한 결과").isNotEmpty();
            softly.assertThat(reportedAfterReplay).as("쓰지 못한 결과는 복구한 프로세스가 저장하며 알린다").isPositive();
            softly.assertThat(after.replays("startup")).isOne();
            softly.assertThat(before.closed.size() + after.closed.size())
                .as("닫힌 예측을 빠뜨리지도 두 번 알리지도 않는다")
                .isEqualTo(all.unresolved().size() + all.scored().size());
            softly.assertThat(after.stored()).isEqualTo(continuous(scenario));
        });
    }

    @Test
    void 줄이_넘쳐_노선을_다시_되감아도_닫힌_예측은_한_번씩만_알린다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                97,
                START,
                500,
                7,
                0,
                true
        );
        Harness harness = new Harness(scenario, scenario.stage(LIMIT));
        int[] passes = passIndexes(harness.events);
        harness.reject = index -> index == passes[passes.length / 3] || index == passes[passes.length / 2] + 1;

        // when
        harness.deliverAll();

        // then
        ScoringStage continuous = scenario.stage(LIMIT);
        List<Object> events = scenario.events();
        ScoringResult all = ScoringScenario.feed(continuous, events).plus(continuous.expire(end(events)));
        assertSoftly(softly -> {
            softly.assertThat(harness.replays("dropped")).isEqualTo(2);
            softly.assertThat(harness.closed).hasSize(all.unresolved().size() + all.scored().size());
        });
    }

    @Test
    void 진단_수신자가_실패해도_쓰기는_성공으로_끝난다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                151,
                START,
                200,
                6,
                0,
                true
        );
        Harness harness = new Harness(scenario, scenario.stage(LIMIT));
        harness.service.setTelemetry(new ForecastTelemetry() {
            @Override
            public void closed(List<ForecastClosed> closed) {
                throw new IllegalStateException("수신 실패 흉내");
            }
        });

        // when
        harness.deliverAll();

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.counter("write.failures")).isZero();
            softly.assertThat(harness.stored()).isEqualTo(continuous(scenario)).isNotEmpty();
        });
    }

    @Test
    void 되감기가_실패한_노선은_30초_동안_사건을_처리하지_않고_그_뒤에_되감아_결과를_맞춘다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                107,
                START,
                500,
                7,
                0,
                true
        );
        Harness harness = new Harness(scenario, scenario.stage(LIMIT));
        harness.failingReads = 1;

        // when
        harness.deliverAll();

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.replays("not_ready", false)).isOne();
            softly.assertThat(harness.replays("failure")).isOne();
            softly.assertThat(harness.counter("skipped")).isPositive();
            softly.assertThat(harness.stored()).isEqualTo(continuous(scenario)).isNotEmpty();
        });
    }

    @Test
    void 쓰지_못한_결과를_남기고_30분_내려가_있다_다시_떠도_그_전에_발행된_예측을_모두_닫고_연속_실행과_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                127,
                START,
                700,
                7,
                0,
                true
        );

        // when
        Map<String, Object> stored = storedAfterCrashAndDowntime(scenario, Duration.ofMinutes(30));

        // then
        assertSoftly(softly -> {
            softly.assertThat(longestWaitAtEnd(scenario)).isGreaterThan(Duration.ofMinutes(100));
            softly.assertThat(stored).isEqualTo(continuous(scenario)).isNotEmpty();
        });
    }

    @Test
    void 쓰지_못한_결과를_남기고_3시간_내려가_있다_다시_떠도_그_전에_발행된_예측을_모두_닫고_연속_실행과_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                127,
                START,
                700,
                7,
                0,
                true
        );

        // when
        Map<String, Object> stored = storedAfterCrashAndDowntime(scenario, Duration.ofHours(3));

        // then
        assertThat(stored).isEqualTo(continuous(scenario)).isNotEmpty();
    }

    @Test
    void 오래_내려가_있다_되감은_직후에_다시_내려가도_닫히지_못한_예측을_다시_읽어_연속_실행과_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                173,
                START,
                700,
                7,
                0,
                true
        );
        Harness first = new Harness(scenario, scenario.stage(LIMIT));
        first.deliverUntilCrash(first.events.size());
        Harness second = first.restartedAfter(Duration.ofHours(3), scenario.stage(LIMIT));
        second.service.start();
        second.service.flush();
        Harness third = second.restartedAfter(Duration.ofMinutes(30), scenario.stage(LIMIT));

        // when
        third.service.start();
        third.closeIdle();

        // then
        assertSoftly(softly -> {
            softly.assertThat(second.stage.waitingCount()).isPositive();
            softly.assertThat(merged(first, second, third)).isEqualTo(continuous(scenario));
        });
    }

    @Test
    void 수집_구멍이_있던_날에_다시_떠도_그_구멍으로_닫혔어야_할_예측을_지원_안_함으로_닫아_연속_실행과_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                131,
                START,
                600,
                7,
                0.004,
                true
        );
        Harness before = new Harness(scenario, scenario.stage(LIMIT));
        final int firstHole = firstHoleIndex(before.events);
        final int cut = firstHole + (before.events.size() - firstHole) / 2;
        before.deliver(0, cut);
        Harness after = before.restarted(scenario.stage(LIMIT));

        // when
        after.service.start();
        after.deliver(cut, after.events.size());

        // then
        Set<Long> publishedAfterRestart = forecastObservations(after.events.subList(cut, after.events.size()));
        assertSoftly(softly -> {
            softly.assertThat(merged(before, after)).isEqualTo(continuous(scenario));
            softly.assertThat(after.stored().values())
                .filteredOn(UnresolvedForecast.class::isInstance)
                .map(UnresolvedForecast.class::cast)
                .filteredOn(closed -> publishedAfterRestart.contains(closed.vehicleObservationId()))
                .extracting(UnresolvedForecast::reason)
                .contains(ScoringReasons.SOURCE_HISTORY_INCOMPLETE);
        });
    }

    @Test
    void 되감기가_오래_실패하다_성공하면_그사이_닫힌_통과의_정답이_빠지지_않고_연속_실행과_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                137,
                START,
                1300,
                7,
                0,
                true
        );
        Harness harness = new Harness(scenario, scenario.stage(LIMIT));
        final int failFrom = harness.events.size() * 15 / 100;
        final int failUntil = harness.events.size() * 75 / 100;
        harness.reject = index -> index == failFrom;
        harness.failingReadAt = index -> index >= failFrom && index < failUntil;

        // when
        harness.deliverAll();

        // then
        Duration failed = Duration.between(time(harness.events.get(failFrom)), time(harness.events.get(failUntil)));
        assertSoftly(softly -> {
            softly.assertThat(failed).isGreaterThan(ScoringReplay.DEFAULT_FORECAST_WINDOW);
            softly.assertThat(harness.replays("failure", false)).isPositive();
            softly.assertThat(harness.replays("failure")).isOne();
            softly.assertThat(harness.stored()).isEqualTo(continuous(scenario)).isNotEmpty();
        });
    }

    @Test
    void 채점_스레드에_중단_요청이_오면_기동_직후_되감기를_돌던_노선까지만_하고_멈춘다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                139,
                START,
                80,
                5,
                0,
                true
        );
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RecordedScoringEvents recorded = new RecordedScoringEvents(new ArrayList<>(), List::of,
            () -> Thread.currentThread().interrupt());
        ScoringService service = new ScoringService(
                task -> {
                    task.run();
                    return true;
                },
                scenario.stage(LIMIT),
                new ScoringReplay(recorded.forecasts(), recorded.passes()),
                new ScoringWriter(new MemoryLabels(), TransactionOperations.withoutTransaction()),
                from -> List.of(1L, 2L, 3L),
                ScoringReplay.DEFAULT_MAX_FORECAST_WINDOW,
                new MutableClock(),
                meters
        );

        // when
        final boolean started = service.start();
        final boolean interrupted = Thread.interrupted();

        // then
        assertSoftly(softly -> {
            softly.assertThat(started).isTrue();
            softly.assertThat(interrupted).as("복구 도중에 온 중단 요청이 남아 있다").isTrue();
            softly.assertThat(meters.find("salmonbus.scoring.replays").tag("reason", "startup").counters())
                .extracting(Counter::count).containsExactly(1.0);
        });
    }

    /**
     * 이벤트 목록 가운데쯤에서, 마지막 주기 쓰기(50번째 이벤트마다) 뒤에 정산을 마친 예측이 하나 이상 남는 끊는 자리를 찾는다. 그 자리에서
     * 멈추면 쓰지 못한 결과가 남는다.
     */
    private static int cutLeavingUnwrittenResults(ScoringScenario scenario) {
        List<Object> events = scenario.events();
        ScoringStage stage = scenario.stage(LIMIT);
        int unwritten = 0;
        for (int index = 0; index < events.size(); index++) {
            unwritten += ScoringScenario.accept(stage, events.get(index)).closed().size();
            if (index % 50 == 0) {
                unwritten = 0;
                continue;
            }
            if (index >= events.size() / 2 && unwritten > 0) {
                return index + 1;
            }
        }
        throw new AssertionError("쓰지 못한 결과가 남는 자리가 없는 시나리오다");
    }

    /** 주기 쓰기 뒤에 나온 결과를 쓰지 못한 채 멈추고, 그만큼 내려가 있다가 다시 떠서 이벤트 없이 마감만 닫은 뒤 저장된 줄을 모은다. */
    private static Map<String, Object> storedAfterCrashAndDowntime(ScoringScenario scenario, Duration downtime) {
        Harness before = new Harness(scenario, scenario.stage(LIMIT));
        before.deliverUntilCrash(before.events.size());
        Harness after = before.restartedAfter(downtime, scenario.stage(LIMIT));
        after.service.start();
        after.closeIdle();
        return merged(before, after);
    }

    /**
     * 마지막 이벤트 때까지 닫히지 못한 예측 가운데 가장 오래 기다린 시간이다. 내려가 있던 시간이 짧을 때는 이 시간이 길어야, 복구가
     * 고정 2시간 10분만 읽던 때와의 차이가 드러난다.
     */
    private static Duration longestWaitAtEnd(ScoringScenario scenario) {
        List<Object> events = scenario.events();
        ScoringStage stage = scenario.stage(LIMIT);
        ScoringScenario.feed(stage, events);
        Map<Long, Instant> confirmedAt = new HashMap<>();
        for (Object event : events) {
            if (event instanceof ForecastPublished published) {
                published.vehicles().forEach(vehicle -> confirmedAt.put(vehicle.vehicleObservationId(),
                    published.confirmedAt()));
            }
        }
        Instant last = time(events.getLast());
        return stage.expire(end(events)).unresolved().stream()
            .map(closed -> Duration.between(confirmedAt.get(closed.vehicleObservationId()), last))
            .max(Duration::compareTo)
            .orElse(Duration.ZERO);
    }

    private static Map<String, Object> merged(Harness... harnesses) {
        Map<String, Object> merged = new HashMap<>();
        for (Harness harness : harnesses) {
            harness.stored().forEach(merged::putIfAbsent);
        }
        return merged;
    }

    private static int firstHoleIndex(List<Object> events) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) instanceof ObservationBatchSeen seen && seen.incomplete()) {
                return index;
            }
        }
        throw new AssertionError("수집 구멍이 없는 시나리오다");
    }

    private static Set<Long> forecastObservations(List<Object> events) {
        Set<Long> observations = new HashSet<>();
        for (Object event : events) {
            if (event instanceof ForecastPublished published) {
                published.vehicles().forEach(vehicle -> observations.add(vehicle.vehicleObservationId()));
            }
        }
        return observations;
    }

    private static int[] passIndexes(List<Object> events) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) instanceof PassClosed) {
                indexes.add(index);
            }
        }
        return indexes.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Map<String, Object> continuous(ScoringScenario scenario) {
        ScoringStage stage = scenario.stage(LIMIT);
        List<Object> events = scenario.events();
        ScoringResult result = ScoringScenario.feed(stage, events).plus(stage.expire(end(events)));
        Map<String, Object> rows = new HashMap<>();
        result.labels().forEach(label -> rows.putIfAbsent(key(label), label));
        result.unresolved().forEach(closed -> rows.putIfAbsent(key(closed), closed));
        return rows;
    }

    private static Instant end(List<Object> events) {
        return time(events.getLast()).plus(Duration.ofHours(3));
    }

    private static String key(PassLabel label) {
        return "label:" + List.of(
                label.sourceDate(),
                label.routeId(),
                label.tripStartObservationId(),
                label.stopOrder(),
                label.capacity(),
                label.labelVersion(),
                label.modelDeploymentId()
        );
    }

    private static String key(UnresolvedForecast closed) {
        return "unresolved:" + List.of(closed.sourceDate(), closed.vehicleObservationId(), closed.targetStopOrder());
    }

    private static Instant time(Object event) {
        return switch (event) {
            case PassClosed pass -> pass.closedAt();
            case ForecastPublished published -> published.confirmedAt();
            case ObservationBatchSeen seen -> seen.receivedAt();
            default -> throw new IllegalArgumentException(String.valueOf(event));
        };
    }

    /** 배치의 기록을 저장한 뒤 그 배치의 이벤트를 정산에 넘기는 흉내다. */
    private static final class Harness {

        private final ScoringScenario scenario;
        private final List<Object> events;
        private final ScoringStage stage;
        private final MutableClock clock;
        private final List<Object> saved;
        private final List<MemoryLabels> generations;
        private final MemoryLabels labels;
        private final List<ForecastClosed> closed = new ArrayList<>();
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final ScoringService service;
        private IntPredicate reject = index -> false;
        private IntPredicate failingReadAt = index -> false;
        private int current = -1;
        private int failingReads;
        private int submitted;

        private Harness(ScoringScenario scenario, ScoringStage stage) {
            this(
                    scenario,
                    stage,
                    new MutableClock(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new MemoryLabels()
            );
        }

        private Harness(
            ScoringScenario scenario,
            ScoringStage stage,
            MutableClock clock,
            List<Object> saved,
            List<MemoryLabels> generations,
            MemoryLabels labels
        ) {
            this.scenario = scenario;
            this.events = scenario.events();
            this.stage = stage;
            this.clock = clock;
            this.saved = saved;
            this.generations = generations;
            this.labels = labels;
            if (!generations.contains(labels)) {
                generations.add(labels);
            }
            RecordedScoringEvents recorded = new RecordedScoringEvents(saved, this::savedRows, this::failRead);
            this.service = new ScoringService(
                    task -> {
                        submitted++;
                        if (reject.test(current)) {
                            return false;
                        }
                        task.run();
                        return true;
                    },
                    stage,
                    new ScoringReplay(recorded.forecasts(), recorded.passes()),
                    new ScoringWriter(labels, TransactionOperations.withoutTransaction()),
                    from -> List.of(ScoringScenario.ROUTE),
                    ScoringReplay.DEFAULT_MAX_FORECAST_WINDOW,
                    clock,
                    meters
            );
            this.service.setTelemetry(new ForecastTelemetry() {
                @Override
                public void closed(List<ForecastClosed> reported) {
                    closed.addAll(reported);
                }
            });
        }

        /** 같은 저장소를 보는 새 프로세스를 만든다. 정답, 미해결 저장은 새로 시작해 이 프로세스가 저장한 행만 따로 볼 수 있게 한다. */
        private Harness restarted(ScoringStage fresh) {
            return new Harness(
                    scenario,
                    fresh,
                    clock,
                    saved,
                    generations,
                    new MemoryLabels()
            );
        }

        /** 정답, 미해결 저장까지 이어받는 새 프로세스를 만든다. 앞 프로세스가 저장한 행은 이미 있는 키로 건너뛴다. */
        private Harness restartedOnSameStore(ScoringStage fresh) {
            return new Harness(
                    scenario,
                    fresh,
                    clock,
                    saved,
                    generations,
                    labels
            );
        }

        private Harness restartedAfter(Duration downtime, ScoringStage fresh) {
            clock.at = clock.at.plus(downtime);
            return restarted(fresh);
        }

        private void deliverAll() {
            deliver(0, events.size());
        }

        private int deliverFirstBatch() {
            int last = 0;
            while (!(events.get(last) instanceof ForecastPublished)) {
                last++;
            }
            saveBatchAt(0);
            List<ForecastBatchEvents.Event> inputs = events.subList(0, last + 1).stream()
                .map(this::input)
                .toList();
            current = 0;
            service.committed(new ForecastBatchEvents(inputs));
            current = -1;
            return last + 1;
        }

        private ForecastBatchEvents.Event input(Object event) {
            return switch (event) {
                case ForecastPublished published -> published;
                case PassClosed pass -> new ForecastBatchEvents.ClosedPasses(List.of(pass));
                case ObservationBatchSeen seen -> seen;
                default -> throw new IllegalArgumentException(String.valueOf(event));
            };
        }

        private void deliver(final int from, final int to) {
            deliverUntilCrash(to, from);
            service.flush();
            if (to == events.size()) {
                closeIdle();
            }
        }

        /** 주기 쓰기만 하고 마지막 쓰기 없이 멈춘다. 마지막 주기 쓰기 뒤에 나온 결과는 저장되지 않는다. */
        private void deliverUntilCrash(final int to) {
            deliverUntilCrash(to, 0);
        }

        private void deliverUntilCrash(final int to, final int from) {
            for (int index = from; index < to; index++) {
                current = index;
                Object event = events.get(index);
                if (event instanceof ObservationBatchSeen) {
                    saveBatchAt(index);
                }
                service.committed(new ForecastBatchEvents(List.of(input(event))));
                if (index % 50 == 0) {
                    service.flush();
                }
            }
            current = -1;
        }

        /**
         * 수집 이벤트로 시작하는 배치의 이벤트를 예보 이벤트까지 한꺼번에 저장하고 시계를 그 배치의 처리 시작 시각 뒤로 옮긴다. 이벤트는 저장을
         * 커밋한 뒤에만 넘어가기 때문이다.
         */
        private void saveBatchAt(final int index) {
            int last = index;
            while (!(events.get(last) instanceof ForecastPublished)) {
                last++;
            }
            saved.addAll(events.subList(index, last + 1));
            Instant committed = time(events.get(last)).plusMillis(1);
            if (committed.isAfter(clock.at)) {
                clock.at = committed;
            }
        }

        /** 이벤트가 더 오지 않는 노선의 마감을 현재 시각으로 닫고 결과를 쓴다. */
        private void closeIdle() {
            clock.at = clock.at.plus(Duration.ofHours(3));
            service.tick();
            service.flush();
        }

        private void failRead() {
            if (failingReads > 0) {
                failingReads--;
                throw new IllegalStateException("읽기 실패 흉내");
            }
            if (failingReadAt.test(current)) {
                throw new IllegalStateException("읽기 실패 흉내");
            }
        }

        /** 지금까지 뜬 모든 프로세스가 저장한 정답 기록과 미해결 기록이다. */
        private List<Object> savedRows() {
            return generations.stream()
                .flatMap(generation -> generation.rows.values().stream())
                .toList();
        }

        private Map<String, Object> stored() {
            return labels.rows;
        }

        private double counter(String name) {
            Counter counter = meters.find("salmonbus.scoring." + name).counter();
            return countOf(counter);
        }

        private double replays(String reason) {
            return replays(reason, true);
        }

        private double replays(String reason, final boolean succeeded) {
            Counter counter = meters.find("salmonbus.scoring.replays").tag("reason", reason)
                .tag("result", resultTag(succeeded)).counter();
            return countOf(counter);
        }

        private static String resultTag(final boolean succeeded) {
            if (succeeded) {
                return "succeeded";
            }
            return "failed";
        }

        /** 한 번도 늘지 않은 계수기는 등록되지 않으므로 0으로 본다. */
        private static double countOf(Counter counter) {
            if (counter == null) {
                return 0;
            }
            return counter.count();
        }
    }

    private static final class MemoryLabels implements PassLabelStore {

        private final Map<String, Object> rows = new HashMap<>();
        private boolean failing;

        @Override
        public List<PassLabel> saveLabels(List<PassLabel> labels) {
            failIfAsked();
            List<PassLabel> inserted = new ArrayList<>();
            for (PassLabel label : labels) {
                if (rows.putIfAbsent(key(label), label) == null) {
                    inserted.add(label);
                }
            }
            return inserted;
        }

        @Override
        public List<UnresolvedForecast> saveUnresolved(List<UnresolvedForecast> unresolved) {
            failIfAsked();
            List<UnresolvedForecast> inserted = new ArrayList<>();
            for (UnresolvedForecast closed : unresolved) {
                if (rows.putIfAbsent(key(closed), closed) == null) {
                    inserted.add(closed);
                }
            }
            return inserted;
        }

        private void failIfAsked() {
            if (failing) {
                throw new IllegalStateException("쓰기 실패 흉내");
            }
        }
    }

    private static final class MutableClock extends Clock {

        private Instant at = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return at;
        }
    }
}
