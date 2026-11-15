package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.ForecastRun;
import com.gustler.backend.forecasting.application.pipeline.PassLabel;
import com.gustler.backend.forecasting.application.pipeline.PassLabelCalculator;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.ScoringReplay;
import com.gustler.backend.forecasting.application.pipeline.ScoringResult;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.application.pipeline.ScoringWriter;
import com.gustler.backend.forecasting.application.pipeline.ShadowScoringService;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.application.pipeline.UnresolvedForecast;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 그림자 채점이 사건을 버리거나 처리 중 예외가 나거나 다시 떠도, 저장된 기록을 되감아 연속 실행과 같은 결과 집합을 쓰는지 본다.
 * 줄은 넣는 즉시 같은 스레드에서 실행하고, 저장소는 사건이 오기 전에 그 사건을 이미 저장한 것처럼 메모리에 둔다.
 */
class ShadowScoringServiceTest {
    private static final int LIMIT = 150_000;
    private static final Instant START = Instant.parse("2026-01-04T21:00:00Z");

    @Test
    void 줄이_넘쳐_버린_사건이_있어도_그_노선을_되감아_연속_실행과_결과_집합이_같다() {
        // given
        LegacyScoringScenario scenario = LegacyScoringScenario.random(97, START, 500, 7, 0, true);
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
        LegacyScoringScenario scenario = LegacyScoringScenario.random(101, START, 500, 7, 0, true);
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
        LegacyScoringScenario scenario = LegacyScoringScenario.random(103, START, 500, 7, 0, true);
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
        LegacyScoringScenario scenario = LegacyScoringScenario.random(107, START, 500, 7, 0, true);
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

    private static int[] passIndexes(List<Object> events) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) instanceof PassClosed) {
                indexes.add(index);
            }
        }
        return indexes.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Map<String, Object> continuous(LegacyScoringScenario scenario) {
        ScoringStage stage = scenario.stage(LIMIT);
        List<Object> events = scenario.events();
        ScoringResult result = LegacyScoringScenario.feed(stage, events).plus(stage.expire(end(events)));
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

    private static boolean isReceivedBetween(
        ForecastPublished event,
        final long routeId,
        Instant fromInclusive,
        Instant toExclusive
    ) {
        return event.routeId() == routeId
            && !event.receivedAt().isBefore(fromInclusive) && event.receivedAt().isBefore(toExclusive);
    }

    private static boolean isClosedBetween(
        PassClosed pass,
        final long routeId,
        Instant fromInclusive,
        Instant toExclusive
    ) {
        return pass.routeId() == routeId
            && !pass.closedAt().isBefore(fromInclusive) && pass.closedAt().isBefore(toExclusive);
    }

    /** 사건을 저장한 뒤 그림자 채점에 넘기는 흉내다. */
    private static final class Harness {
        private final LegacyScoringScenario scenario;
        private final List<Object> events;
        private final ScoringStage stage;
        private final MutableClock clock;
        private final List<ForecastPublished> forecasts;
        private final List<PassClosed> passes;
        private final MemoryLabels labels;
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final ShadowScoringService service;
        private IntPredicate reject = index -> false;
        private int current = -1;
        private int failingReads;

        private Harness(LegacyScoringScenario scenario, ScoringStage stage) {
            this(scenario, stage, new MutableClock(), new ArrayList<>(), new ArrayList<>(), new MemoryLabels());
        }

        private Harness(
            LegacyScoringScenario scenario,
            ScoringStage stage,
            MutableClock clock,
            List<ForecastPublished> forecasts,
            List<PassClosed> passes,
            MemoryLabels labels
        ) {
            this.scenario = scenario;
            this.events = scenario.events();
            this.stage = stage;
            this.clock = clock;
            this.forecasts = forecasts;
            this.passes = passes;
            this.labels = labels;
            MemoryForecasts records = new MemoryForecasts();
            this.service = new ShadowScoringService(task -> {
                if (reject.test(current)) {
                    return false;
                }
                task.run();
                return true;
            }, stage, new ScoringReplay(records, new MemoryPasses()),
                new ScoringWriter(labels, TransactionOperations.withoutTransaction()),
                from -> List.of(LegacyScoringScenario.ROUTE), ScoringReplay.DEFAULT_FORECAST_WINDOW, clock, meters);
        }

        private Harness restarted(ScoringStage fresh) {
            return new Harness(scenario, fresh, clock, forecasts, passes, new MemoryLabels());
        }

        private void deliverAll() {
            deliver(0, events.size());
            clock.at = end(events);
            service.tick();
            service.flush();
        }

        private void deliver(final int from, final int to) {
            for (int index = from; index < to; index++) {
                current = index;
                Object event = events.get(index);
                clock.at = time(event).plusMillis(1);
                switch (event) {
                    case ForecastPublished published -> {
                        forecasts.add(published);
                        service.forecastPublished(published);
                    }
                    case PassClosed pass -> {
                        passes.add(pass);
                        service.passesClosed(List.of(pass));
                    }
                    case ObservationBatchSeen seen -> service.batchSeen(seen);
                    default -> throw new IllegalArgumentException(String.valueOf(event));
                }
                if (index % 50 == 0) {
                    service.flush();
                }
            }
            current = -1;
            service.flush();
            if (to == events.size()) {
                clock.at = end(events);
                service.tick();
                service.flush();
            }
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

        private final class MemoryForecasts implements ForecastRecordStore {
            @Override
            public boolean save(ForecastPublished published) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<ForecastRun> findLatestRun(final long routeVersionId, Set<ForecastOutcome> outcomes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ForecastPublished.VehicleForecast> findRecords(
                LocalDate sourceDate,
                final long observationBatchId
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ForecastPublished> findPublishedBetween(
                final long routeId,
                Instant fromInclusive,
                Instant toExclusive
            ) {
                if (failingReads > 0) {
                    failingReads--;
                    throw new IllegalStateException("읽기 실패 흉내");
                }
                return forecasts.stream()
                    .filter(event -> isReceivedBetween(event, routeId, fromInclusive, toExclusive))
                    .toList();
            }
        }

        private final class MemoryPasses implements StopPassStore {
            @Override
            public int save(List<PassClosed> saved) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<PassClosed> findClosedBetween(
                final long routeId,
                Instant fromInclusive,
                Instant toExclusive
            ) {
                return passes.stream()
                    .filter(pass -> isClosedBetween(pass, routeId, fromInclusive, toExclusive))
                    .toList();
            }
        }
    }

    private static final class MemoryLabels implements PassLabelStore {
        private final Map<String, Object> rows = new HashMap<>();

        @Override
        public int saveLabels(List<PassLabel> labels) {
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
            int inserted = 0;
            for (UnresolvedForecast closed : unresolved) {
                if (rows.putIfAbsent(key(closed), closed) == null) {
                    inserted++;
                }
            }
            return inserted;
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
