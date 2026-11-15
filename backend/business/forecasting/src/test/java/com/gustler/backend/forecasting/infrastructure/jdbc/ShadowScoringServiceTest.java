package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.application.pipeline.PassLabel;
import com.gustler.backend.forecasting.application.pipeline.PassLabelCalculator;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.ScoringReasons;
import com.gustler.backend.forecasting.application.pipeline.ScoringReplay;
import com.gustler.backend.forecasting.application.pipeline.ScoringResult;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.application.pipeline.ScoringWriter;
import com.gustler.backend.forecasting.application.pipeline.ShadowScoringService;
import com.gustler.backend.forecasting.application.pipeline.UnresolvedForecast;
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
 * 채점이 사건을 버리거나 처리 중 예외가 나거나 다시 떠도, 저장된 기록을 되감아 연속 실행과 같은 결과 집합을 쓰는지 본다.
 * 줄은 넣는 즉시 같은 스레드에서 실행한다. 저장소는 메모리에 두고, 예보 경로처럼 배치의 실행 줄(수집 사실 포함)과 통과를 먼저
 * 저장한 뒤에 그 배치의 사건을 넘긴다.
 */
class ShadowScoringServiceTest {
    private static final int LIMIT = 150_000;
    private static final Instant START = Instant.parse("2026-01-04T21:00:00Z");

    @Test
    void 줄이_넘쳐_버린_사건이_있어도_그_노선을_되감아_연속_실행과_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(97, START, 500, 7, 0, true);
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
        ScoringScenario scenario = ScoringScenario.random(101, START, 500, 7, 0, true);
        AtomicInteger calls = new AtomicInteger();
        PassLabelCalculator real = new ConfirmedPassLabelCalculator();
        PassLabelCalculator failingOnce = (pass, previous, evidence) -> {
            if (calls.incrementAndGet() == 40) {
                throw new IllegalStateException("계산 실패 흉내");
            }
            return real.calculate(pass, previous, evidence);
        };
        Harness harness = new Harness(scenario, scenario.stage(LIMIT, failingOnce));

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
        ScoringScenario scenario = ScoringScenario.random(103, START, 500, 7, 0, true);
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
    void 되감기가_실패한_노선은_30초_동안_사건을_처리하지_않고_그_뒤에_되감아_결과를_맞춘다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(107, START, 500, 7, 0, true);
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
        ScoringScenario scenario = ScoringScenario.random(127, START, 700, 7, 0, true);

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
        ScoringScenario scenario = ScoringScenario.random(127, START, 700, 7, 0, true);

        // when
        Map<String, Object> stored = storedAfterCrashAndDowntime(scenario, Duration.ofHours(3));

        // then
        assertThat(stored).isEqualTo(continuous(scenario)).isNotEmpty();
    }

    @Test
    void 오래_내려가_있다_되감은_직후에_다시_내려가도_닫히지_못한_예측을_다시_읽어_연속_실행과_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(173, START, 700, 7, 0, true);
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
        ScoringScenario scenario = ScoringScenario.random(131, START, 600, 7, 0.004, true);
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
        ScoringScenario scenario = ScoringScenario.random(137, START, 1300, 7, 0, true);
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
    void 받기를_멈춘_뒤에_온_사건과_주기_호출은_채점_줄에_넣지_않고_버린_사건으로도_세지_않는다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(139, START, 80, 5, 0, true);
        Harness harness = new Harness(scenario, scenario.stage(LIMIT));
        final int half = harness.events.size() / 2;
        harness.deliver(0, half);
        final int submittedBefore = harness.submitted;

        // when
        harness.service.stopAccepting();
        harness.service.stopAccepting();
        harness.deliver(half, harness.events.size());
        final boolean started = harness.service.start();
        final boolean ticked = harness.service.tick();

        // then
        assertSoftly(softly -> {
            softly.assertThat(harness.submitted).isEqualTo(submittedBefore);
            softly.assertThat(harness.counter("dropped")).isZero();
            softly.assertThat(started).isFalse();
            softly.assertThat(ticked).isFalse();
        });
    }

    @Test
    void 마지막_쓰기는_쓰지_못하면_남은_줄_수를_돌려주고_다시_부르면_남은_줄을_모두_쓴다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(149, START, 80, 5, 0, true);
        Harness harness = new Harness(scenario, scenario.stage(LIMIT));
        harness.labels.failing = true;
        harness.deliver(0, harness.events.size() / 2);
        harness.service.stopAccepting();

        // when
        final int remainingWhileFailing = harness.service.flushRemaining();
        harness.labels.failing = false;
        final int remainingAfterRetry = harness.service.flushRemaining();
        final int remainingAfterAnother = harness.service.flushRemaining();

        // then
        assertSoftly(softly -> {
            softly.assertThat(remainingWhileFailing).isPositive();
            softly.assertThat(remainingAfterRetry).isZero();
            softly.assertThat(remainingAfterAnother).isZero();
            softly.assertThat(harness.stored()).hasSize(remainingWhileFailing);
            softly.assertThat(harness.counter("write.failures")).isPositive();
        });
    }

    /** 주기 쓰기 뒤에 나온 결과를 쓰지 못한 채 멈추고, 그만큼 내려가 있다가 다시 떠서 사건 없이 마감만 닫은 뒤 저장된 줄을 모은다. */
    private static Map<String, Object> storedAfterCrashAndDowntime(ScoringScenario scenario, Duration downtime) {
        Harness before = new Harness(scenario, scenario.stage(LIMIT));
        before.deliverUntilCrash(before.events.size());
        Harness after = before.restartedAfter(downtime, scenario.stage(LIMIT));
        after.service.start();
        after.closeIdle();
        return merged(before, after);
    }

    /**
     * 마지막 사건 때까지 닫히지 못한 예측 가운데 가장 오래 기다린 시간이다. 내려가 있던 시간이 짧을 때는 이 시간이 길어야, 되감기가
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
        return "label:" + List.of(label.sourceDate(), label.routeId(), label.tripStartObservationId(),
            label.stopOrder(), label.capacity(), label.labelVersion(), label.modelDeploymentId());
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

    /** 배치의 기록을 저장한 뒤 그 배치의 사건을 채점에 넘기는 흉내다. */
    private static final class Harness {
        private final ScoringScenario scenario;
        private final List<Object> events;
        private final ScoringStage stage;
        private final MutableClock clock;
        private final List<Object> saved;
        private final List<MemoryLabels> generations;
        private final MemoryLabels labels = new MemoryLabels();
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final ShadowScoringService service;
        private IntPredicate reject = index -> false;
        private IntPredicate failingReadAt = index -> false;
        private int current = -1;
        private int failingReads;
        private int submitted;

        private Harness(ScoringScenario scenario, ScoringStage stage) {
            this(scenario, stage, new MutableClock(), new ArrayList<>(), new ArrayList<>());
        }

        private Harness(
            ScoringScenario scenario,
            ScoringStage stage,
            MutableClock clock,
            List<Object> saved,
            List<MemoryLabels> generations
        ) {
            this.scenario = scenario;
            this.events = scenario.events();
            this.stage = stage;
            this.clock = clock;
            this.saved = saved;
            this.generations = generations;
            generations.add(labels);
            RecordedScoringEvents recorded = new RecordedScoringEvents(saved, this::savedRows, this::failRead);
            this.service = new ShadowScoringService(task -> {
                submitted++;
                if (reject.test(current)) {
                    return false;
                }
                task.run();
                return true;
            }, stage, new ScoringReplay(recorded.forecasts(), recorded.passes()),
                new ScoringWriter(labels, TransactionOperations.withoutTransaction()),
                from -> List.of(ScoringScenario.ROUTE), ScoringReplay.DEFAULT_MAX_FORECAST_WINDOW, clock, meters);
        }

        /** 같은 저장소를 보는 새 프로세스를 만든다. 정답·미해결 저장은 새로 시작해 이 프로세스가 쓴 줄만 따로 볼 수 있게 한다. */
        private Harness restarted(ScoringStage fresh) {
            return new Harness(scenario, fresh, clock, saved, generations);
        }

        private Harness restartedAfter(Duration downtime, ScoringStage fresh) {
            clock.at = clock.at.plus(downtime);
            return restarted(fresh);
        }

        private void deliverAll() {
            deliver(0, events.size());
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
                switch (event) {
                    case ForecastPublished published -> service.forecastPublished(published);
                    case PassClosed pass -> service.passesClosed(List.of(pass));
                    case ObservationBatchSeen seen -> service.batchSeen(seen);
                    default -> throw new IllegalArgumentException(String.valueOf(event));
                }
                if (index % 50 == 0) {
                    service.flush();
                }
            }
            current = -1;
        }

        /**
         * 수집 사건으로 시작하는 배치의 사건을 예보 사건까지 한꺼번에 저장하고 시계를 그 배치의 착수 시각 뒤로 옮긴다. 사건은 저장을
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

        /** 사건이 더 오지 않는 노선의 마감을 벽시계로 닫고 결과를 쓴다. */
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

        /** 지금까지 뜬 모든 프로세스가 저장한 정답 줄과 미해결 줄이다. */
        private List<Object> savedRows() {
            return generations.stream()
                .flatMap(generation -> generation.rows.values().stream())
                .toList();
        }

        private Map<String, Object> stored() {
            return labels.rows;
        }

        private double counter(String name) {
            Counter counter = meters.find("salmonbus.pipeline.scoring." + name).counter();
            return countOf(counter);
        }

        private double replays(String reason) {
            return replays(reason, true);
        }

        private double replays(String reason, final boolean succeeded) {
            Counter counter = meters.find("salmonbus.pipeline.scoring.replays").tag("reason", reason)
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
        public int saveLabels(List<PassLabel> labels) {
            failIfAsked();
            int inserted = 0;
            for (PassLabel label : labels) {
                if (rows.putIfAbsent(key(label), label) == null) {
                    inserted++;
                }
            }
            return inserted;
        }

        @Override
        public int saveUnresolved(List<UnresolvedForecast> unresolved) {
            failIfAsked();
            int inserted = 0;
            for (UnresolvedForecast closed : unresolved) {
                if (rows.putIfAbsent(key(closed), closed) == null) {
                    inserted++;
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
