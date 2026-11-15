package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.ForecastRun;
import com.gustler.backend.forecasting.application.pipeline.PassLabel;
import com.gustler.backend.forecasting.application.pipeline.ScoringReplay;
import com.gustler.backend.forecasting.application.pipeline.ScoringResult;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.application.pipeline.UnresolvedForecast;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ScoringStageLegacyEquivalenceTest {
    private static final Instant START = Instant.parse("2026-01-05T00:00:00Z");
    private static final int LIMIT = 150_000;

    @Test
    void 준비_경계_사이에_30분이_지나_버린_차량도_옛_정산과_예측마다_같다() {
        // given
        Drives scenario = new Drives();
        final int last = 5000;
        drive(scenario, "expire-d", last - 80, 20, List.of(1, 1, 2, 2, 3), List.of(30, 28, 25, 22, 20));
        drive(scenario, "expire-f", last - 70, 20, List.of(1, 1, 2, 2, 3), List.of(31, 29, 26, 23, 21));
        drive(scenario, "expire-e", last + 1799, 1, List.of(1, 1), List.of(10, 10));
        drive(scenario, "expire-d", last + 1800, 20, List.of(3, 3, 4, 4, 5), List.of(20, 18, 16, 14, 12));
        drive(scenario, "expire-e", last + 1811, 1, List.of(1), List.of(10));
        drive(scenario, "expire-f", last + 1812, 20, List.of(3, 3, 4, 4, 5), List.of(21, 19, 17, 15, 13));

        // when
        LegacyScoringScenario.Comparison comparison = scenario.build().compare(LIMIT);

        // then
        assertEquivalent(comparison);
        assertThat(comparison.legacyOutcomes()).containsKey("SETTLED:null");
    }

    @Test
    void 자정부터_02시까지_이어지는_운행도_옛_정산과_예측마다_같다() {
        // given
        Drives scenario = new Drives();
        final int midnight = 15 * 3600;
        drive(scenario, "night-g", midnight - 120, 20, List.of(1, 1, 2, 2, 3, 3, 4, 4, 5),
            List.of(30, 29, 27, 25, 23, 21, 19, 17, 15));
        drive(scenario, "night-h", midnight - 90, 20, List.of(1, 1, 2, 2, 3, 3, 4, 4, 5),
            List.of(31, 30, 28, 26, 24, 22, 20, 18, 16));

        // when
        LegacyScoringScenario.Comparison comparison = scenario.build().compare(LIMIT);

        // then
        assertEquivalent(comparison);
        assertSoftly(softly -> {
            softly.assertThat(comparison.midnightScope()).isPositive();
            softly.assertThat(comparison.legacyOutcomes()).containsKey("SETTLED:null");
        });
    }

    @Test
    void 여러_차량과_끊긴_연결이_섞여도_옛_정산과_예측마다_같다() {
        // given
        Drives scenario = new Drives();
        drive(scenario, "multi-a", 1000, 20, List.of(1, 1, 2, 2, 3, 3), List.of(30, 28, 25, 22, 20, 18));
        drive(scenario, "multi-c", 1005, 20, List.of(1, 1, 2, 2, 3, 3, 4),
            Arrays.asList(30, 30, null, null, 20, 20, 15));
        drive(scenario, "multi-b", 1010, 20, List.of(1, 1, 2), List.of(35, 33, 30));
        drive(scenario, "multi-b", 1040, 1, List.of(2), List.of(31));
        drive(scenario, "multi-b", 1070, 20, List.of(2, 3, 3, 4, 4, 5), List.of(29, 27, 26, 24, 23, 21));
        drive(scenario, "multi-a", 1220, 20, List.of(3, 4, 4, 5), List.of(18, 15, 12, 10));

        // when
        LegacyScoringScenario.Comparison comparison = scenario.build().compare(LIMIT);

        // then
        assertEquivalent(comparison);
        assertThat(comparison.legacyOutcomes()).containsKey("SETTLED:null");
    }

    @Test
    void 출근_시간_무작위_운행_700배치도_옛_정산과_예측마다_같다() {
        // given
        LegacyScoringScenario scenario = LegacyScoringScenario.random(11, Instant.parse("2026-01-04T21:00:00Z"), 700, 7,
            0, true);

        // when
        LegacyScoringScenario.Comparison comparison = scenario.compare(LIMIT);

        // then
        assertEquivalent(comparison);
        assertThat(comparison.legacyOutcomes()).containsKeys("SETTLED:null", "LOST:NO_CLOSED_IDENTITY_SAFE_PASS",
            "UNSUPPORTED:UNSUPPORTED_HEADWAY", "UNSUPPORTED:NO_PREVIOUS_PASS",
            "UNSUPPORTED:CAPACITY_EVIDENCE_MISMATCH");
    }

    @Test
    void 낮에서_퇴근_시간으로_넘어가는_무작위_운행도_옛_정산과_예측마다_같다() {
        // given
        LegacyScoringScenario scenario = LegacyScoringScenario.random(23, Instant.parse("2026-01-05T07:00:00Z"), 700, 7,
            0, true);

        // when
        LegacyScoringScenario.Comparison comparison = scenario.compare(LIMIT);

        // then
        assertEquivalent(comparison);
        assertThat(comparison.legacyOutcomes()).containsKeys("SETTLED:null",
            "UNSUPPORTED:NO_FITTED_STOP_PARAMETERS");
    }

    @Test
    void 자정을_넘는_무작위_운행도_자정_범위_차이_밖에서는_옛_정산과_같다() {
        // given
        LegacyScoringScenario withoutExtended = LegacyScoringScenario.random(37, Instant.parse("2026-01-05T14:00:00Z"),
            600, 6, 0, false);
        LegacyScoringScenario withExtended = LegacyScoringScenario.random(71, Instant.parse("2026-01-05T14:20:00Z"),
            600, 8, 0, true, List.of());

        // when
        LegacyScoringScenario.Comparison outside = withoutExtended.compare(LIMIT);
        LegacyScoringScenario.Comparison extended = withExtended.compare(LIMIT);

        // then
        assertEquivalent(outside);
        assertEquivalent(extended);
        assertSoftly(softly -> {
            softly.assertThat(outside.legacyOutcomes()).containsKey("UNSUPPORTED:OUTSIDE_FITTED_BAND");
            softly.assertThat(extended.legacyOutcomes()).containsKeys("SETTLED:null", "UNSUPPORTED:NO_PREVIOUS_PASS");
        });
    }

    @Test
    void 수집_구멍과_관측_상한이_있는_무작위_운행은_구멍_시점_차이_밖에서는_옛_정산과_같다() {
        // given
        LegacyScoringScenario holes = LegacyScoringScenario.random(41, Instant.parse("2026-01-04T21:30:00Z"), 600, 6,
            0.004, true);
        LegacyScoringScenario overflow = LegacyScoringScenario.random(43, Instant.parse("2026-01-04T21:30:00Z"), 600, 6,
            0, true);

        // when
        LegacyScoringScenario.Comparison withHoles = holes.compare(LIMIT);
        LegacyScoringScenario.Comparison withLimit = overflow.compare(500);

        // then
        assertEquivalent(withHoles);
        assertEquivalent(withLimit);
        assertSoftly(softly -> {
            softly.assertThat(withHoles.legacyOutcomes()).containsKey("UNSUPPORTED:SOURCE_HISTORY_INCOMPLETE");
            softly.assertThat(withLimit.legacyOutcomes()).containsKey("UNSUPPORTED:OBSERVATION_INPUT_LIMIT");
        });
    }

    @Test
    void 같은_사건을_두_번_넣어도_결과_집합이_같다() {
        // given
        LegacyScoringScenario scenario = LegacyScoringScenario.random(53, Instant.parse("2026-01-04T21:00:00Z"), 400, 6,
            0.002, true);
        List<Object> events = scenario.events();
        List<Object> doubled = new ArrayList<>();
        for (Object event : events) {
            doubled.add(event);
            doubled.add(event);
        }

        // when
        ScoringResult once = LegacyScoringScenario.feed(scenario.stage(LIMIT), events);
        ScoringResult twice = LegacyScoringScenario.feed(scenario.stage(LIMIT), doubled);

        // then
        assertSoftly(softly -> {
            softly.assertThat(Set.copyOf(twice.labels())).isEqualTo(Set.copyOf(once.labels()));
            softly.assertThat(Set.copyOf(twice.unresolved())).isEqualTo(Set.copyOf(once.unresolved()));
            softly.assertThat(Set.copyOf(twice.scored())).isEqualTo(Set.copyOf(once.scored()));
            softly.assertThat(once.labels()).isNotEmpty();
        });
    }

    @Test
    void 중간에_상태를_버리고_되감은_뒤_이어_가도_연속_실행과_결과_집합이_같다() {
        // given
        LegacyScoringScenario scenario = LegacyScoringScenario.random(61, Instant.parse("2026-01-05T14:30:00Z"), 600, 6,
            0, true);
        List<Object> events = scenario.events();
        LegacyScoringScenario.Scored continuous = scenario.scored(
            LegacyScoringScenario.feed(scenario.stage(LIMIT), events));
        Map<String, Object> continuousRows = storedRows(LegacyScoringScenario.feed(scenario.stage(LIMIT), events));

        for (int cut : List.of(events.size() / 3, events.size() / 2, events.size() * 4 / 5)) {
            ScoringStage stage = scenario.stage(LIMIT);
            ScoringResult before = LegacyScoringScenario.feed(stage, events.subList(0, cut));
            Instant now = events.subList(0, cut).stream().map(ScoringStageLegacyEquivalenceTest::time)
                .max(Instant::compareTo).orElseThrow().plusMillis(1);

            // when
            ScoringResult replayed = new ScoringReplay(new MemoryForecasts(events.subList(0, cut)),
                new MemoryPasses(events.subList(0, cut))).replay(stage, LegacyScoringScenario.ROUTE, now);
            ScoringResult after = LegacyScoringScenario.feed(stage, events.subList(cut, events.size()));

            // then
            ScoringResult all = before.plus(replayed).plus(after);
            assertSoftly(softly -> {
                softly.assertThat(storedRows(all)).as("cut=%d", cut).isEqualTo(continuousRows);
                softly.assertThat(scenario.scored(all).verdicts()).as("cut=%d", cut).isEqualTo(continuous.verdicts());
            });
        }
    }

    private static Map<String, Object> storedRows(ScoringResult result) {
        Map<String, Object> rows = new LinkedHashMap<>();
        for (PassLabel label : result.labels()) {
            rows.putIfAbsent("label:" + List.of(label.sourceDate(), label.routeId(), label.tripStartObservationId(),
                label.stopOrder(), label.capacity(), label.labelVersion(), label.modelDeploymentId()), label);
        }
        for (UnresolvedForecast closed : result.unresolved()) {
            rows.putIfAbsent("unresolved:" + List.of(closed.sourceDate(), closed.vehicleObservationId(),
                closed.targetStopOrder()), closed);
        }
        return new HashMap<>(rows);
    }

    private static Instant time(Object event) {
        return switch (event) {
            case PassClosed pass -> pass.closedAt();
            case ForecastPublished published -> published.confirmedAt();
            case ObservationBatchSeen seen -> seen.receivedAt();
            default -> throw new IllegalArgumentException(String.valueOf(event));
        };
    }

    private static void assertEquivalent(LegacyScoringScenario.Comparison comparison) {
        System.out.printf("equivalence forecasts=%d equal=%d midnight=%d taint=%d unexplained=%d outcomes=%s%n",
            comparison.forecasts(), comparison.equal(), comparison.midnightScope(), comparison.taintTiming(),
            comparison.unexplained().size(), comparison.legacyOutcomes());
        assertSoftly(softly -> {
            softly.assertThat(comparison.unexplained())
                .as("설명되지 않는 차이: %s", comparison.unexplained().stream().limit(10).map(String::valueOf)
                    .collect(Collectors.joining("\n")))
                .isEmpty();
            softly.assertThat(comparison.forecasts()).isPositive();
        });
    }

    /** 옛 정산 통합 테스트의 {@code drive}와 같다. 관측을 모아 두었다가 {@link Drives#build()}에서 시각 순으로 넣는다. */
    private static void drive(
        Drives drives,
        String vehicle,
        final int start,
        final int step,
        List<Integer> stops,
        List<Integer> seats
    ) {
        for (int i = 0; i < stops.size(); i++) {
            drives.observations.add(new Drive(start + i * step, vehicle, stops.get(i), seats.get(i)));
        }
    }

    private record Drive(
        int seconds,
        String vehicle,
        int stop,
        Integer seats
    ) {
    }

    /** 관측마다 배치 하나이고 좌석이 있는 관측은 모든 목표(1~5)를 정원 40으로 예측한다. */
    private static final class Drives {
        private final List<Drive> observations = new ArrayList<>();

        private LegacyScoringScenario build() {
            List<Integer> real = List.of(1, 2, 3, 4, 5);
            LegacyScoringScenario scenario = new LegacyScoringScenario(real, 9,
                LegacyScoringScenario.context(real, 9, List.of(1, 2, 3, 4, 5), true, List.of(), List.of()));
            observations.stream().sorted(Comparator.comparingInt(Drive::seconds)).forEach(drive -> {
                final long batch = scenario.batch(START.plusSeconds(drive.seconds()), false);
                final long observation = scenario.row(batch, drive.vehicle(), drive.stop(), drive.seats());
                if (drive.seats() != null) {
                    scenario.forecast(observation, 40, 1, 2, 3, 4, 5);
                }
            });
            return scenario;
        }
    }

    private static final class MemoryForecasts implements ForecastRecordStore {
        private final List<ForecastPublished> published;

        private MemoryForecasts(List<Object> events) {
            published = events.stream().filter(ForecastPublished.class::isInstance).map(ForecastPublished.class::cast)
                .toList();
        }

        @Override
        public boolean save(ForecastPublished event) {
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
            return published.stream()
                .filter(event -> isReceivedBetween(event, routeId, fromInclusive, toExclusive))
                .toList();
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
    }

    private static final class MemoryPasses implements StopPassStore {
        private final List<PassClosed> passes;

        private MemoryPasses(List<Object> events) {
            passes = events.stream().filter(PassClosed.class::isInstance).map(PassClosed.class::cast).toList();
        }

        @Override
        public int save(List<PassClosed> saved) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PassClosed> findClosedBetween(final long routeId, Instant fromInclusive, Instant toExclusive) {
            return passes.stream()
                .filter(pass -> isClosedBetween(pass, routeId, fromInclusive, toExclusive))
                .toList();
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
    }
}
