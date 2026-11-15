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
import com.gustler.backend.forecasting.application.pipeline.RecordedBatch;
import com.gustler.backend.forecasting.application.pipeline.ScoringReasons;
import com.gustler.backend.forecasting.application.pipeline.ScoringReplay;
import com.gustler.backend.forecasting.application.pipeline.ScoringResult;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.application.pipeline.UnresolvedForecast;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 채점 되감기가 예보·통과·수집 사실을 어디서부터 읽는지와, 수집 구멍·관측 상한이 있던 날에 되감아도 연속 실행과 같은 결과를 내는지
 * 본다.
 */
class ScoringReplayTest {
    private static final Instant START = Instant.parse("2026-01-04T21:00:00Z");
    private static final Instant NOW = Instant.parse("2026-01-05T03:00:00Z");
    private static final int LIMIT = 150_000;

    @Test
    void 정답이_저장된_마지막_통과가_닫힌_시각에서_2시간_10분_앞의_예보부터_다시_읽는다() {
        // given
        Instant scoredThrough = NOW.minus(Duration.ofHours(3));
        RecordingStores stores = new RecordingStores(Optional.of(scoredThrough), Optional.empty());
        ScoringReplay replay = new ScoringReplay(stores, stores);

        // when
        replay.replay(emptyStage(), ScoringScenario.ROUTE, NOW);

        // then
        Instant forecastFrom = scoredThrough.minus(Duration.ofMinutes(130));
        assertSoftly(softly -> {
            softly.assertThat(stores.scoredFrom).containsOnly(NOW.minus(Duration.ofHours(26)));
            softly.assertThat(stores.forecastFrom).isEqualTo(forecastFrom);
            softly.assertThat(stores.passFrom).isEqualTo(forecastFrom.minus(Duration.ofHours(23).plusMinutes(50)));
            softly.assertThat(stores.recordedFrom).isEqualTo(Instant.parse("2026-01-04T14:50:00Z"));
            softly.assertThat(stores.readUntil).containsOnly(NOW);
        });
    }

    @Test
    void 중단_없이_되감으면_예보는_2시간_10분_통과는_26시간_남짓을_읽는다() {
        // given
        Instant scoredThrough = NOW.minusSeconds(5);
        RecordingStores stores = new RecordingStores(Optional.of(scoredThrough), Optional.empty());
        ScoringReplay replay = new ScoringReplay(stores, stores);

        // when
        replay.replay(emptyStage(), ScoringScenario.ROUTE, NOW);

        // then
        assertSoftly(softly -> {
            softly.assertThat(stores.forecastFrom).isEqualTo(NOW.minus(Duration.ofMinutes(130)).minusSeconds(5));
            softly.assertThat(stores.passFrom).isEqualTo(NOW.minus(Duration.ofHours(26)).minusSeconds(5));
        });
    }

    @Test
    void 미해결_줄이_저장된_예측이_정답이_저장된_통과보다_늦으면_그_예측의_착수_시각을_기준으로_읽는다() {
        // given
        Instant lastLabeled = NOW.minus(Duration.ofHours(9));
        Instant lastUnresolved = NOW.minus(Duration.ofHours(4));
        RecordingStores stores = new RecordingStores(Optional.of(lastLabeled), Optional.of(lastUnresolved));
        ScoringReplay replay = new ScoringReplay(stores, stores);

        // when
        replay.replay(emptyStage(), ScoringScenario.ROUTE, NOW);

        // then
        assertThat(stores.forecastFrom).isEqualTo(lastUnresolved.minus(Duration.ofMinutes(130)));
    }

    @Test
    void 저장된_정답도_미해결_줄도_없으면_예보를_상한인_26시간_앞부터_읽는다() {
        // given
        RecordingStores stores = new RecordingStores(Optional.empty(), Optional.empty());
        ScoringReplay replay = new ScoringReplay(stores, stores);

        // when
        replay.replay(emptyStage(), ScoringScenario.ROUTE, NOW);

        // then
        assertThat(stores.forecastFrom).isEqualTo(NOW.minus(Duration.ofHours(26)));
    }

    @Test
    void 정답이_저장된_마지막_통과가_상한_가까이로_오래됐으면_예보를_상한보다_앞에서는_읽지_않는다() {
        // given
        RecordingStores stores = new RecordingStores(Optional.of(NOW.minus(Duration.ofHours(25))), Optional.empty());
        ScoringReplay replay = new ScoringReplay(stores, stores);

        // when
        replay.replay(emptyStage(), ScoringScenario.ROUTE, NOW);

        // then
        assertThat(stores.forecastFrom).isEqualTo(NOW.minus(Duration.ofHours(26)));
    }

    @Test
    void 수집_구멍이_있던_날에_상태를_버리고_되감아도_연속_실행과_결과_집합이_같다() {
        // given
        ScoringScenario scenario = ScoringScenario.random(151, START, 600, 6, 0.004, true);
        List<Object> events = scenario.events();
        Map<String, Object> continuous = storedRows(ScoringScenario.feed(scenario.stage(LIMIT), events));
        final int firstHole = firstHoleIndex(events);

        for (int cut : List.of(batchStart(events, firstHole + 1),
            batchStart(events, firstHole + (events.size() - firstHole) / 2))) {
            // when
            Map<String, Object> replayed = storedRowsWithReplayAt(scenario, events, cut, LIMIT);

            // then
            assertThat(replayed).as("cut=%d", cut).isEqualTo(continuous);
        }
        assertThat(reasons(continuous)).contains(ScoringReasons.SOURCE_HISTORY_INCOMPLETE);
    }

    @Test
    void 관측_상한을_넘기_전과_넘은_뒤_어디서_되감아도_연속_실행과_결과_집합이_같다() {
        // given
        final int limit = 500;
        ScoringScenario scenario = ScoringScenario.random(157, START, 600, 6, 0, true);
        List<Object> events = scenario.events();
        Map<String, Object> continuous = storedRows(ScoringScenario.feed(scenario.stage(limit), events));
        final int overflow = overflowIndex(events, limit);

        for (int cut : List.of(batchStart(events, overflow / 2),
            batchStart(events, overflow + (events.size() - overflow) / 2))) {
            // when
            Map<String, Object> replayed = storedRowsWithReplayAt(scenario, events, cut, limit);

            // then
            assertThat(replayed).as("cut=%d", cut).isEqualTo(continuous);
        }
        assertThat(reasons(continuous)).contains(ScoringReasons.OBSERVATION_INPUT_LIMIT);
    }

    /** {@code cut} 앞의 사건을 저장된 것으로 보고 그 자리에서 상태를 버리고 되감은 뒤 나머지 사건을 이어 넣는다. */
    private static Map<String, Object> storedRowsWithReplayAt(
        ScoringScenario scenario,
        List<Object> events,
        final int cut,
        final int limit
    ) {
        ScoringStage stage = scenario.stage(limit);
        List<Object> saved = events.subList(0, cut);
        ScoringResult before = ScoringScenario.feed(stage, saved);
        Instant now = saved.stream().map(ScoringReplayTest::time).max(Instant::compareTo).orElseThrow().plusMillis(1);
        RecordedScoringEvents recorded = new RecordedScoringEvents(saved,
            () -> RecordedScoringEvents.rowsOf(before));
        ScoringResult replayed = new ScoringReplay(recorded.forecasts(), recorded.passes())
            .replay(stage, ScoringScenario.ROUTE, now);
        ScoringResult after = ScoringScenario.feed(stage, events.subList(cut, events.size()));
        return storedRows(before.plus(replayed).plus(after));
    }

    private static Map<String, Object> storedRows(ScoringResult result) {
        Map<String, Object> rows = new HashMap<>();
        for (PassLabel label : result.labels()) {
            rows.putIfAbsent("label:" + List.of(label.sourceDate(), label.routeId(), label.tripStartObservationId(),
                label.stopOrder(), label.capacity(), label.labelVersion(), label.modelDeploymentId()), label);
        }
        for (UnresolvedForecast closed : result.unresolved()) {
            rows.putIfAbsent("unresolved:" + List.of(closed.sourceDate(), closed.vehicleObservationId(),
                closed.targetStopOrder()), closed);
        }
        return rows;
    }

    private static List<String> reasons(Map<String, Object> rows) {
        return rows.values().stream()
            .filter(UnresolvedForecast.class::isInstance)
            .map(UnresolvedForecast.class::cast)
            .map(UnresolvedForecast::reason)
            .distinct()
            .toList();
    }

    private static int firstHoleIndex(List<Object> events) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) instanceof ObservationBatchSeen seen && seen.incomplete()) {
                return index;
            }
        }
        throw new AssertionError("수집 구멍이 없는 시나리오다");
    }

    /** 수집 사건의 줄 수를 더해 가다 하루 상한을 처음 넘는 배치의 수집 사건 자리를 돌려준다. */
    private static int overflowIndex(List<Object> events, final int limit) {
        int rows = 0;
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) instanceof ObservationBatchSeen seen) {
                rows += seen.countedRows();
            }
            if (rows > limit) {
                return index;
            }
        }
        throw new AssertionError("관측 상한을 넘지 않는 시나리오다");
    }

    /** {@code from}부터 처음 나오는 배치의 첫 사건(수집 사건) 자리를 돌려준다. 저장된 사건이 배치 단위로 끊기게 한다. */
    private static int batchStart(List<Object> events, final int from) {
        for (int index = from; index < events.size(); index++) {
            if (events.get(index) instanceof ObservationBatchSeen) {
                return index;
            }
        }
        throw new AssertionError("뒤에 배치가 없다: " + from);
    }

    private static Instant time(Object event) {
        return switch (event) {
            case PassClosed pass -> pass.closedAt();
            case ForecastPublished published -> published.confirmedAt();
            case ObservationBatchSeen seen -> seen.receivedAt();
            default -> throw new IllegalArgumentException(String.valueOf(event));
        };
    }

    private static ScoringStage emptyStage() {
        return new ScoringStage((deployment, version) -> Optional.empty(), version -> List.of(),
            new ConfirmedPassLabelCalculator(), ScoringScenario.FIXED);
    }

    /** 아무 기록도 돌려주지 않고 되감기가 읽은 범위만 적어 둔다. */
    private static final class RecordingStores implements ForecastRecordStore, StopPassStore {
        private final Optional<Instant> lastLabeled;
        private final Optional<Instant> lastUnresolved;
        private final List<Instant> readUntil = new ArrayList<>();
        private final List<Instant> scoredFrom = new ArrayList<>();
        private Instant forecastFrom;
        private Instant passFrom;
        private Instant recordedFrom;

        private RecordingStores(Optional<Instant> lastLabeled, Optional<Instant> lastUnresolved) {
            this.lastLabeled = lastLabeled;
            this.lastUnresolved = lastUnresolved;
        }

        @Override
        public Optional<Instant> findLastScoredClosedAt(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            scoredFrom.add(fromInclusive);
            readUntil.add(toExclusive);
            return lastLabeled;
        }

        @Override
        public Optional<Instant> findLastUnresolvedConfirmedAt(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            scoredFrom.add(fromInclusive);
            readUntil.add(toExclusive);
            return lastUnresolved;
        }

        @Override
        public List<ForecastPublished> findPublishedBetween(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            forecastFrom = fromInclusive;
            readUntil.add(toExclusive);
            return List.of();
        }

        @Override
        public List<PassClosed> findClosedBetween(final long routeId, Instant fromInclusive, Instant toExclusive) {
            passFrom = fromInclusive;
            readUntil.add(toExclusive);
            return List.of();
        }

        @Override
        public List<RecordedBatch> findRecordedBatchesBetween(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            recordedFrom = fromInclusive;
            readUntil.add(toExclusive);
            return List.of();
        }

        @Override
        public boolean save(ForecastPublished published) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean save(ForecastPublished published, ObservationBatchSeen seen) {
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
        public int save(List<PassClosed> passes) {
            throw new UnsupportedOperationException();
        }
    }
}
