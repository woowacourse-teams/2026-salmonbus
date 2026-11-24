package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.application.forecast.ForecastRecordStore;
import com.gustler.backend.forecasting.application.forecast.PassLabel;
import com.gustler.backend.forecasting.application.forecast.RecordedBatch;
import com.gustler.backend.forecasting.application.forecast.ScoringReplay;
import com.gustler.backend.forecasting.application.forecast.ScoringResult;
import com.gustler.backend.forecasting.application.forecast.ScoringStage;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.application.forecast.UnresolvedForecast;
import java.time.Instant;
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

/**
 * 정산 단계가 {@link ScoringScenario}의 흐름에서 기록해 둔 기대값({@link ScoringScenarioBaseline})과 예측마다 같은 결과를 내는지
 * 확인한다. 기대값은 다시 만들 수 없으므로 흐름을 만드는 순서와 씨앗, 배치 수, 차량 수를 바꾸지 않는다.
 */
class ScoringStageBaselineTest {

    private static final Instant START = Instant.parse("2026-01-05T00:00:00Z");
    private static final int LIMIT = 150_000;

    @Test
    void 준비_경계_사이에_30분이_지나_버린_차량도_예측마다_기준_기록과_같다() {
        // given
        Drives scenario = new Drives();
        final int last = 5000;
        drive(
                scenario,
                "expire-d",
                last - 80,
                20,
                List.of(
                        1,
                        1,
                        2,
                        2,
                        3
                ),
                List.of(
                        30,
                        28,
                        25,
                        22,
                        20
                )
        );
        drive(
                scenario,
                "expire-f",
                last - 70,
                20,
                List.of(
                        1,
                        1,
                        2,
                        2,
                        3
                ),
                List.of(
                        31,
                        29,
                        26,
                        23,
                        21
                )
        );
        drive(
                scenario,
                "expire-e",
                last + 1799,
                1,
                List.of(1, 1),
                List.of(10, 10)
        );
        drive(
                scenario,
                "expire-d",
                last + 1800,
                20,
                List.of(
                        3,
                        3,
                        4,
                        4,
                        5
                ),
                List.of(
                        20,
                        18,
                        16,
                        14,
                        12
                )
        );
        drive(
                scenario,
                "expire-e",
                last + 1811,
                1,
                List.of(1),
                List.of(10)
        );
        drive(
                scenario,
                "expire-f",
                last + 1812,
                20,
                List.of(
                        3,
                        3,
                        4,
                        4,
                        5
                ),
                List.of(
                        21,
                        19,
                        17,
                        15,
                        13
                )
        );

        // when
        ScoringScenarioBaseline.Comparison comparison = compare(scenario.build(), "expired-gap", LIMIT);

        // then
        assertSameAsBaseline(comparison);
        assertThat(comparison.expectedOutcomes()).containsKey("SETTLED:null");
    }

    @Test
    void 자정부터_02시까지_이어지는_운행도_예측마다_기준_기록과_같다() {
        // given
        Drives scenario = new Drives();
        final int midnight = 15 * 3600;
        drive(
                scenario,
                "night-g",
                midnight - 120,
                20,
                List.of(
                        1,
                        1,
                        2,
                        2,
                        3,
                        3,
                        4,
                        4,
                        5
                ),
                List.of(
                        30,
                        29,
                        27,
                        25,
                        23,
                        21,
                        19,
                        17,
                        15
                )
        );
        drive(
                scenario,
                "night-h",
                midnight - 90,
                20,
                List.of(
                        1,
                        1,
                        2,
                        2,
                        3,
                        3,
                        4,
                        4,
                        5
                ),
                List.of(
                        31,
                        30,
                        28,
                        26,
                        24,
                        22,
                        20,
                        18,
                        16
                )
        );

        // when
        ScoringScenarioBaseline.Comparison comparison = compare(scenario.build(), "overnight", LIMIT);

        // then
        assertSameAsBaseline(comparison);
        assertSoftly(softly -> {
            softly.assertThat(comparison.midnightScope()).isPositive();
            softly.assertThat(comparison.expectedOutcomes()).containsKey("SETTLED:null");
        });
    }

    @Test
    void 여러_차량과_끊긴_연결이_섞여도_예측마다_기준_기록과_같다() {
        // given
        Drives scenario = new Drives();
        drive(
                scenario,
                "multi-a",
                1000,
                20,
                List.of(
                        1,
                        1,
                        2,
                        2,
                        3,
                        3
                ),
                List.of(
                        30,
                        28,
                        25,
                        22,
                        20,
                        18
                )
        );
        drive(
                scenario,
                "multi-c",
                1005,
                20,
                List.of(
                        1,
                        1,
                        2,
                        2,
                        3,
                        3,
                        4
                ),
                Arrays.asList(
                        30,
                        30,
                        null,
                        null,
                        20,
                        20,
                        15
                )
        );
        drive(
                scenario,
                "multi-b",
                1010,
                20,
                List.of(1, 1, 2),
                List.of(35, 33, 30)
        );
        drive(
                scenario,
                "multi-b",
                1040,
                1,
                List.of(2),
                List.of(31)
        );
        drive(
                scenario,
                "multi-b",
                1070,
                20,
                List.of(
                        2,
                        3,
                        3,
                        4,
                        4,
                        5
                ),
                List.of(
                        29,
                        27,
                        26,
                        24,
                        23,
                        21
                )
        );
        drive(
                scenario,
                "multi-a",
                1220,
                20,
                List.of(
                        3,
                        4,
                        4,
                        5
                ),
                List.of(
                        18,
                        15,
                        12,
                        10
                )
        );

        // when
        ScoringScenarioBaseline.Comparison comparison = compare(scenario.build(), "broken-chains", LIMIT);

        // then
        assertSameAsBaseline(comparison);
        assertThat(comparison.expectedOutcomes()).containsKey("SETTLED:null");
    }

    @Test
    void 출근_시간_무작위_운행_700배치도_예측마다_기준_기록과_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                11,
                Instant.parse("2026-01-04T21:00:00Z"),
                700,
                7,
                0,
                true
        );

        // when
        ScoringScenarioBaseline.Comparison comparison = compare(scenario, "morning-11", LIMIT);

        // then
        assertSameAsBaseline(comparison);
        assertThat(comparison.expectedOutcomes()).containsKeys(
                "SETTLED:null",
                "LOST:NO_CLOSED_IDENTITY_SAFE_PASS",
                "UNSUPPORTED:UNSUPPORTED_HEADWAY",
                "UNSUPPORTED:NO_PREVIOUS_PASS",
                "UNSUPPORTED:CAPACITY_EVIDENCE_MISMATCH"
        );
    }

    @Test
    void 낮에서_퇴근_시간으로_넘어가는_무작위_운행도_예측마다_기준_기록과_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                23,
                Instant.parse("2026-01-05T07:00:00Z"),
                700,
                7,
                0,
                true
        );

        // when
        ScoringScenarioBaseline.Comparison comparison = compare(scenario, "evening-23", LIMIT);

        // then
        assertSameAsBaseline(comparison);
        assertThat(comparison.expectedOutcomes()).containsKeys("SETTLED:null", "UNSUPPORTED:NO_FITTED_STOP_PARAMETERS");
    }

    @Test
    void 자정을_넘는_무작위_운행도_자정_범위_차이_밖에서는_기준_기록과_같다() {
        // given
        ScoringScenario withoutExtended = ScoringScenario.random(
                37,
                Instant.parse("2026-01-05T14:00:00Z"),
                600,
                6,
                0,
                false
        );
        ScoringScenario withExtended = ScoringScenario.random(
                71,
                Instant.parse("2026-01-05T14:20:00Z"),
                600,
                8,
                0,
                true,
                List.of()
        );

        // when
        ScoringScenarioBaseline.Comparison outside = compare(withoutExtended, "midnight-37", LIMIT);
        ScoringScenarioBaseline.Comparison extended = compare(withExtended, "midnight-extended-71", LIMIT);

        // then
        assertSameAsBaseline(outside);
        assertSameAsBaseline(extended);
        assertSoftly(softly -> {
            softly.assertThat(outside.expectedOutcomes()).containsKey("UNSUPPORTED:OUTSIDE_FITTED_BAND");
            softly.assertThat(extended.expectedOutcomes()).containsKeys("SETTLED:null", "UNSUPPORTED:NO_PREVIOUS_PASS");
        });
    }

    @Test
    void 수집_구멍과_관측_상한이_있는_무작위_운행은_구멍_시점_차이_밖에서는_기준_기록과_같다() {
        // given
        ScoringScenario holes = ScoringScenario.random(
                41,
                Instant.parse("2026-01-04T21:30:00Z"),
                600,
                6,
                0.004,
                true
        );
        ScoringScenario overflow = ScoringScenario.random(
                43,
                Instant.parse("2026-01-04T21:30:00Z"),
                600,
                6,
                0,
                true
        );

        // when
        ScoringScenarioBaseline.Comparison withHoles = compare(holes, "holes-41", LIMIT);
        ScoringScenarioBaseline.Comparison withLimit = compare(overflow, "row-limit-43", 500);

        // then
        assertSameAsBaseline(withHoles);
        assertSameAsBaseline(withLimit);
        assertSoftly(softly -> {
            softly.assertThat(withHoles.expectedOutcomes()).containsKey("UNSUPPORTED:SOURCE_HISTORY_INCOMPLETE");
            softly.assertThat(withLimit.expectedOutcomes()).containsKey("UNSUPPORTED:OBSERVATION_INPUT_LIMIT");
        });
    }

    @Test
    void 같은_사건을_두_번_넣어도_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(
                53,
                Instant.parse("2026-01-04T21:00:00Z"),
                400,
                6,
                0.002,
                true
        );
        List<Object> events = scenario.events();
        List<Object> doubled = new ArrayList<>();
        for (Object event : events) {
            doubled.add(event);
            doubled.add(event);
        }

        // when
        ScoringResult once = ScoringScenario.feed(scenario.stage(LIMIT), events);
        ScoringResult twice = ScoringScenario.feed(scenario.stage(LIMIT), doubled);

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
        ScoringScenario scenario = ScoringScenario.random(
                61,
                Instant.parse("2026-01-05T14:30:00Z"),
                600,
                6,
                0,
                true
        );
        List<Object> events = scenario.events();
        ScoringScenario.Scored continuous = scenario.scored(ScoringScenario.feed(scenario.stage(LIMIT), events));
        Map<String, Object> continuousRows = storedRows(ScoringScenario.feed(scenario.stage(LIMIT), events));

        for (final int cut : List.of(events.size() / 3, events.size() / 2, events.size() * 4 / 5)) {
            ScoringStage stage = scenario.stage(LIMIT);
            ScoringResult before = ScoringScenario.feed(stage, events.subList(0, cut));
            Instant now = events.subList(0, cut).stream().map(ScoringStageBaselineTest::time)
                .max(Instant::compareTo).orElseThrow().plusMillis(1);

            // when
            ScoringResult replayed = new ScoringReplay(new MemoryForecasts(events.subList(
                0,
                cut
            )),
                new MemoryPasses(events.subList(
                    0,
                    cut
                ))).replay(stage, ScoringScenario.ROUTE, now);
            ScoringResult after = ScoringScenario.feed(stage, events.subList(cut, events.size()));

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
            rows.putIfAbsent("label:" + List.of(
                    label.sourceDate(),
                    label.routeId(),
                    label.tripStartObservationId(),
                    label.stopOrder(),
                    label.capacity(),
                    label.labelVersion(),
                    label.modelDeploymentId()
            ), label);
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

    /** 그 이름의 기대값과 견준다. 관측 상한이 {@code limit}인 정산 단계에 흐름을 다 흘리고 마감한 결과를 쓴다. */
    private static ScoringScenarioBaseline.Comparison compare(ScoringScenario scenario, String name, final int limit) {
        ScoringScenarioBaseline baseline = ScoringScenarioBaseline.read("scoring-stage/" + name);
        return baseline.compare(scenario, scenario.scoredToDeadline(limit));
    }

    private static void assertSameAsBaseline(ScoringScenarioBaseline.Comparison comparison) {
        System.out.printf(
                "baseline forecasts=%d equal=%d midnight=%d taint=%d unexplained=%d outcomes=%s%n",
                comparison.forecasts(),
                comparison.equal(),
                comparison.midnightScope(),
                comparison.taintTiming(),
                comparison.unexplained().size(),
                comparison.expectedOutcomes()
        );
        assertSoftly(softly -> {
            softly.assertThat(comparison.unexplained())
                .as("설명되지 않는 차이: %s", comparison.unexplained().stream()
                    .limit(10)
                    .map(String::valueOf)
                    .collect(Collectors.joining("\n")))
                .isEmpty();
            softly.assertThat(comparison.forecasts()).isPositive();
        });
    }

    /** 차량 하나의 관측을 일정한 간격으로 모아 둔다. {@link Drives#build()}가 시각 순으로 넣는다. */
    private static void drive(
        Drives drives,
        String vehicle,
        final int start,
        final int step,
        List<Integer> stops,
        List<Integer> seats
    ) {
        for (int i = 0; i < stops.size(); i++) {
            drives.observations.add(new Drive(
                    start + i * step,
                    vehicle,
                    stops.get(i),
                    seats.get(i)
            ));
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

        private ScoringScenario build() {
            List<Integer> real = List.of(
                    1,
                    2,
                    3,
                    4,
                    5
            );
            ScoringScenario scenario = new ScoringScenario(real, 9,
                ScoringScenario.context(
                        real,
                        9,
                        List.of(
                                1,
                                2,
                                3,
                                4,
                                5
                        ),
                        true,
                        List.of(),
                        List.of()
                ));
            observations.stream().sorted(
                Comparator.comparingInt(Drive::seconds)
            ).forEach(drive -> {
                final long batch = scenario.batch(START.plusSeconds(drive.seconds()), false);
                final long observation = scenario.row(
                        batch,
                        drive.vehicle(),
                        drive.stop(),
                        drive.seats()
                );
                if (drive.seats() != null) {
                    scenario.forecast(
                            observation,
                            40,
                            1,
                            2,
                            3,
                            4,
                            5
                    );
                }
            });
            return scenario;
        }
    }

    private static final class MemoryForecasts implements ForecastRecordStore {

        private final List<ForecastPublished> published;

        private MemoryForecasts(List<Object> events) {
            published = events.stream()
                .filter(ForecastPublished.class::isInstance)
                .map(ForecastPublished.class::cast)
                .toList();
        }

        @Override
        public boolean save(ForecastPublished event, ObservationBatchSeen seen) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ForecastPublished> findPublishedBetween(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            return published.stream()
                .filter(event -> isReceivedBetween(
                        event,
                        routeId,
                        fromInclusive,
                        toExclusive
                ))
                .toList();
        }

        /** 수집 사실을 돌려주지 않는다. 이 저장소로 복구하는 흐름에는 관측 누락이 없고 관측 행 수가 상한에 못 미친다. */
        @Override
        public List<RecordedBatch> findRecordedBatchesBetween(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            return List.of();
        }

        /** 미해결 기록이 저장된 예측을 모르는 저장소를 흉내 낸다. 복구는 예보 범위의 상한까지 읽는다. */
        @Override
        public Optional<Instant> findLastUnresolvedConfirmedAt(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            return Optional.empty();
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
            passes = events.stream()
                .filter(PassClosed.class::isInstance)
                .map(PassClosed.class::cast)
                .toList();
        }

        @Override
        public int save(List<PassClosed> saved) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PassClosed> findClosedBetween(final long routeId, Instant fromInclusive, Instant toExclusive) {
            return passes.stream()
                .filter(pass -> isClosedBetween(
                        pass,
                        routeId,
                        fromInclusive,
                        toExclusive
                ))
                .toList();
        }

        /** 정답이 저장된 통과를 모르는 저장소를 흉내 낸다. 복구는 예보 범위의 상한까지 읽는다. */
        @Override
        public Optional<Instant> findLastScoredClosedAt(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            return Optional.empty();
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
