package com.gustler.backend.forecasting.application.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareVehiclePrefix;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ForecastStageTest {
    private static final String ROUTE = "stage-route";
    private static final long VERSION = 7;
    private static final Instant T0 = Instant.parse("2026-10-14T01:00:00Z");
    private static final AlightingAwareRouteStatistics STATISTICS = statistics();
    private final ForecastStage stage = new ForecastStage((source, vehicles, at, batch) -> capacities(vehicles));
    private long nextObservation = 1000;

    @Test
    void 확정_조건을_만족하지_않는_배치는_상태와_순위를_바꾸지_않는다() {
        // given
        RouteForecastState state = RouteForecastState.fromVerifiedOrigin(ROUTE);
        List<ForecastBatch> unconfirmed = List.of(
            batch(1, T0, T0.plusSeconds(2), "UNVERIFIED", "SUCCESS_ROWS", rows(row("a", 2, 10))),
            batch(2, T0, T0.plusSeconds(2), "LIVE", "SUCCESS_EMPTY", List.of()),
            batch(3, T0, T0.plusSeconds(91), "LIVE", "SUCCESS_ROWS", rows(row("a", 2, 10))),
            batch(4, T0, T0.minusSeconds(1), "LIVE", "SUCCESS_ROWS", rows(row("a", 2, 10))),
            new ForecastBatch(5, VERSION, T0, T0.plusSeconds(2), "LIVE", "SUCCESS_ROWS", null, List.of()),
            new ForecastBatch(6, VERSION, T0, null, "LIVE", "SUCCESS_ROWS", 1, rows(row("a", 2, 10))));

        // when
        List<ForecastStageResult> results = unconfirmed.stream()
            .map(batch -> stage.advance(state, STATISTICS, "GBIS", batch)).toList();
        ForecastStageResult boundary = stage.advance(state, STATISTICS, "GBIS",
            batch(7, T0, T0.plusSeconds(90), "LIVE", "SUCCESS_ROWS", rows(row("a", 2, 10))));

        // then
        assertSoftly(softly -> {
            for (ForecastStageResult result : results) {
                softly.assertThat(result.status()).isEqualTo(ForecastStageResult.Status.NOT_CONFIRMED);
                softly.assertThat(result.state()).isSameAs(state);
            }
            softly.assertThat(boundary.status()).isEqualTo(ForecastStageResult.Status.FORECAST);
            softly.assertThat(boundary.state().cursor().orElseThrow().rank()).isZero();
            softly.assertThat(boundary.state().observations().getFirst().batchRank()).isZero();
        });
    }

    @Test
    void 관측_줄은_차량_없는_줄과_같은_배치_중복_차량을_빼고_좌석은_0에서_70까지만_값으로_읽는다() {
        // given
        List<ForecastObservationRow> rows = List.of(
            new ForecastObservationRow(1, null, 2, 3, 1, 10, null, 1),
            new ForecastObservationRow(2, "a", 2, 3, 1, 0, null, 2),
            new ForecastObservationRow(3, "b", 2, 3, 1, 10, null, 1),
            new ForecastObservationRow(4, "b", 3, 4, 1, 11, null, 1),
            new ForecastObservationRow(5, "c", 2, 3, 1, 71, null, 1),
            new ForecastObservationRow(6, "d", 2, 3, 1, 10, "REPORTED_UNKNOWN", 1),
            new ForecastObservationRow(7, "e", 2, 3, 1, null, "NOT_REPORTED", 0),
            new ForecastObservationRow(8, "f", 2, 3, 1, 70, null, 4),
            new ForecastObservationRow(9, "g", 10, 11, 1, 5, null, 3),
            new ForecastObservationRow(10, "h", 9, 10, 2, 5, null, 3));
        ForecastBatch batch = batch(1, T0, T0.plusSeconds(3), "LIVE", "SUCCESS_ROWS", rows);

        // when
        List<AlightingAwareObservation> observations = stage.advance(RouteForecastState.fromVerifiedOrigin(ROUTE),
            STATISTICS, "GBIS", batch).state().observations();

        // then
        Map<Long, AlightingAwareObservation> byId = observations.stream()
            .collect(Collectors.toMap(AlightingAwareObservation::observationId, observation -> observation));
        assertSoftly(softly -> {
            softly.assertThat(byId.keySet()).containsExactlyInAnyOrder(2L, 5L, 6L, 7L, 8L, 9L, 10L);
            softly.assertThat(byId.get(2L).seats()).isZero();
            softly.assertThat(byId.get(5L).seats()).isNull();
            softly.assertThat(byId.get(6L).seats()).isNull();
            softly.assertThat(byId.get(7L).seats()).isNull();
            softly.assertThat(byId.get(8L).seats()).isEqualTo(70);
            softly.assertThat(byId.get(2L).down()).isFalse();
            softly.assertThat(byId.get(9L).down()).isTrue();
            softly.assertThat(byId.get(10L).down()).isTrue();
            softly.assertThat(byId.get(2L).receivedAt()).isEqualTo(T0.plusSeconds(3));
            softly.assertThat(byId.get(2L).sourceReceivedAt()).isEqualTo(T0);
            softly.assertThat(byId.get(2L).routeVersionId()).isEqualTo(VERSION);
            softly.assertThat(byId.get(2L).crowd()).isEqualTo(2);
        });
    }

    @Test
    void 줄_수가_저장_줄_수와_다르면_되감기를_요구하고_상태를_그대로_둔다() {
        // given
        RouteForecastState state = RouteForecastState.fromVerifiedOrigin(ROUTE);
        ForecastBatch missing = new ForecastBatch(1, VERSION, T0, T0.plusSeconds(2), "LIVE", "SUCCESS_ROWS", 2,
            rows(row("a", 2, 10)));

        // when
        ForecastStageResult result = stage.advance(state, STATISTICS, "GBIS", missing);

        // then
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo(ForecastStageResult.Status.REPLAY_REQUIRED);
            softly.assertThat(result.reason()).isEqualTo(ForecastStage.SOURCE_ROWS_MISSING);
            softly.assertThat(result.state()).isSameAs(state);
        });
    }

    @Test
    void 이미_반영한_배치를_다시_받으면_상태를_바꾸지_않는다() {
        // given
        ForecastBatch batch = confirmed(1, T0, row("a", 2, 10));
        RouteForecastState state = stage.advance(RouteForecastState.fromVerifiedOrigin(ROUTE), STATISTICS, "GBIS",
            batch).state();

        // when
        ForecastStageResult again = stage.advance(state, STATISTICS, "GBIS", batch);

        // then
        assertSoftly(softly -> {
            softly.assertThat(again.status()).isEqualTo(ForecastStageResult.Status.STALE);
            softly.assertThat(again.state()).isSameAs(state);
        });
    }

    @Test
    void 정원_조회가_실패해도_받은_상태는_그대로이고_다시_처리하면_같은_결과다() {
        // given
        RouteForecastState state = RouteForecastState.fromVerifiedOrigin(ROUTE);
        for (int index = 0; index < 4; index++) {
            state = stage.advance(state, STATISTICS, "GBIS", confirmed(index + 1, T0.plusSeconds(30L * index),
                row("a", 2 + index / 2, 10 + index), row("b", 3, 20))).state();
        }
        RouteForecastState before = state;
        List<AlightingAwareObservation> observations = List.copyOf(before.observations());
        Map<String, AlightingAwareVehiclePrefix> prefix = Map.copyOf(before.prefix());
        List<AlightingAwarePassEvent> events = List.copyOf(before.events());
        Map<String, RouteForecastState.VehicleTrack> vehicles = Map.copyOf(before.vehicles());
        RouteForecastState.Cursor cursor = before.cursor().orElseThrow();
        ForecastBatch next = confirmed(5, T0.plusSeconds(120), row("a", 4, 15), row("b", 3, 20));
        ForecastStage failing = new ForecastStage((source, ids, at, batch) -> {
            throw new IllegalStateException("capacity store down");
        });

        // when
        Throwable failure = catchThrowable(() -> failing.advance(before, STATISTICS, "GBIS", next));
        ForecastStageResult retried = stage.advance(before, STATISTICS, "GBIS", next);
        ForecastStageResult fresh = stage.advance(before, STATISTICS, "GBIS", next);

        // then
        assertSoftly(softly -> {
            softly.assertThat(failure).isInstanceOf(IllegalStateException.class);
            softly.assertThat(before.observations()).isEqualTo(observations);
            softly.assertThat(before.prefix()).isEqualTo(prefix);
            softly.assertThat(before.events()).isEqualTo(events);
            softly.assertThat(before.vehicles()).isEqualTo(vehicles);
            softly.assertThat(before.cursor()).contains(cursor);
            softly.assertThat(retried.state().observations()).isEqualTo(fresh.state().observations());
            softly.assertThat(retried.snapshots()).extracting(AlightingAwareFeatureSnapshot::vehicleObservationId)
                .isEqualTo(fresh.snapshots().stream()
                    .map(AlightingAwareFeatureSnapshot::vehicleObservationId)
                    .toList());
            softly.assertThat(retried.snapshots()).isNotEmpty();
        });
    }

    @Test
    void 연결이_끊기면_그_뒤에_닫힌_통과는_끊긴_뒤_첫_관측을_연결_시작으로_든다() {
        // given
        RouteForecastState state = RouteForecastState.fromVerifiedOrigin(ROUTE);
        int[] passed = {1, 1, 2, 2, 3, 3, -1, -1, -1, -1, 3, 4, 4, 5, 5, 6};
        List<ClosedPass> closed = new ArrayList<>();
        List<Long> observations = new ArrayList<>();

        // when
        for (int index = 0; index < passed.length; index++) {
            if (passed[index] < 0) {
                continue;
            }
            ForecastObservationRow row = row("a", passed[index], 20 - index);
            observations.add(row.observationId());
            ForecastStageResult result = stage.advance(state, STATISTICS, "GBIS",
                confirmed(index + 1, T0.plusSeconds(30L * index), row));
            closed.addAll(result.closedPasses());
            state = result.state();
        }

        // then
        final long firstChain = observations.getFirst();
        final long secondChain = observations.get(6);
        RouteForecastState last = state;
        Map<Integer, Long> chainByStop = new HashMap<>();
        closed.forEach(pass -> chainByStop.put(pass.event().stopOrder(), pass.chainStartObservationId()));
        assertSoftly(softly -> {
            softly.assertThat(chainByStop).containsOnlyKeys(2, 4, 5);
            softly.assertThat(chainByStop.get(2)).isEqualTo(firstChain);
            softly.assertThat(chainByStop.get(4)).isEqualTo(secondChain);
            softly.assertThat(chainByStop.get(5)).isEqualTo(secondChain);
            softly.assertThat(closed).allSatisfy(pass -> softly.assertThat(pass.reachedKnownSeatAt()).isNotNull());
            softly.assertThat(last.vehicles().get("a").chainStartObservationId()).isEqualTo(secondChain);
        });
    }

    @Test
    void 되감은_상태는_창_시작_뒤_90분까지_예보하지_않고_운행_시작을_보지_못한_차량은_다음_운행까지_뺀다() {
        // given
        RouteForecastState state = RouteForecastState.replayingFrom(ROUTE, T0);
        Map<Integer, Map<String, String>> outcomes = new HashMap<>();

        // when
        for (int index = 0; index < 240; index++) {
            Instant at = T0.plusSeconds(10 + 30L * index);
            List<ForecastObservationRow> rows = new ArrayList<>();
            rows.add(row("a", Math.min(1 + index / 22, 10), 20));
            if (index >= 80) {
                rows.add(row("b", 2 + (index - 80) / 30, 30));
            }
            ForecastStageResult result = stage.advance(state, STATISTICS, "GBIS", confirmed(index + 1, at, rows));
            outcomes.put(index, outcome(result));
            state = result.state();
        }

        // then
        RouteForecastState last = state;
        assertSoftly(softly -> {
            softly.assertThat(outcomes.get(0)).containsEntry("a", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
            softly.assertThat(outcomes.get(178)).containsEntry("a", ForecastStage.REPLAY_HISTORY_INCOMPLETE)
                .containsEntry("b", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
            softly.assertThat(outcomes.get(181)).containsEntry("a", ForecastStage.TRIP_START_UNKNOWN)
                .containsEntry("b", "FORECAST");
            softly.assertThat(outcomes.get(197)).containsEntry("a", ForecastStage.TRIP_START_UNKNOWN);
            softly.assertThat(outcomes.get(198)).containsEntry("a", "FORECAST");
            softly.assertThat(last.tripStartKnown("a")).isTrue();
            softly.assertThat(last.replayFrom()).contains(T0);
        });
    }

    @Test
    void 되감기_창_시작에_걸친_연결이_다음_실정류장에_닿기_전과_닿은_뒤_90분_동안은_노선의_예보를_뺀다() {
        // given
        RouteForecastState state = RouteForecastState.replayingFrom(ROUTE, T0);
        Map<Integer, Map<String, String>> outcomes = new HashMap<>();

        // when
        for (int index = 0; index < 600; index++) {
            Instant at = T0.plusSeconds(10 + 30L * index);
            List<ForecastObservationRow> rows = new ArrayList<>();
            rows.add(row("c", passedBeforeOrAfterWindow(index), 20));
            if (index >= 100) {
                rows.add(row("b", 2 + (index - 100) / 60, 30));
            }
            ForecastStageResult result = stage.advance(state, STATISTICS, "GBIS", confirmed(index + 1, at, rows));
            outcomes.put(index, outcome(result));
            state = result.state();
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(outcomes.get(200)).containsEntry("b", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
            softly.assertThat(outcomes.get(249)).containsEntry("b", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
            softly.assertThat(outcomes.get(430)).containsEntry("b", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
            softly.assertThat(outcomes.get(431)).containsEntry("b", "FORECAST")
                .containsEntry("c", ForecastStage.TRIP_START_UNKNOWN);
        });
    }

    @Test
    void 창_시작에_걸친_연결이_놓친_통과가_다른_차량의_입력을_바꾸는_동안은_빼고_그_뒤에는_연속_실행과_같다() {
        // given
        List<ForecastBatch> batches = new ArrayList<>();
        for (int index = 0; index < 650; index++) {
            List<ForecastObservationRow> rows = new ArrayList<>();
            rows.add(row("c", passedAcrossWindow(index), 20));
            if (index >= 200) {
                rows.add(row("b", 1, 30));
            }
            batches.add(confirmed(index + 1, T0.minusSeconds(3600).plusSeconds(10 + 30L * index), rows));
        }
        RouteForecastState continuous = RouteForecastState.fromVerifiedOrigin(ROUTE);
        RouteForecastState replayed = RouteForecastState.replayingFrom(ROUTE, T0);
        Map<Integer, Map<String, String>> outcomes = new HashMap<>();
        int compared = 0;

        // when
        for (int index = 0; index < batches.size(); index++) {
            ForecastStageResult expected = stage.advance(continuous, STATISTICS, "GBIS", batches.get(index));
            continuous = expected.state();
            if (batches.get(index).confirmedAt().isBefore(T0)) {
                continue;
            }
            ForecastStageResult actual = stage.advance(replayed, STATISTICS, "GBIS", batches.get(index));
            replayed = actual.state();
            outcomes.put(index, outcome(actual));
            Map<Long, List<String>> expectedVectors = vectors(expected.snapshots());
            for (Map.Entry<Long, List<String>> entry : vectors(actual.snapshots()).entrySet()) {
                assertThat(entry.getValue()).as("배치 %d 관측 %d", index, entry.getKey())
                    .isEqualTo(expectedVectors.get(entry.getKey()));
                compared++;
            }
        }

        // then
        final int checked = compared;
        assertSoftly(softly -> {
            softly.assertThat(outcomes.get(369)).containsEntry("b", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
            softly.assertThat(outcomes.get(370)).containsEntry("b", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
            softly.assertThat(outcomes.get(550)).containsEntry("b", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
            softly.assertThat(outcomes.get(551)).containsEntry("b", "FORECAST");
            softly.assertThat(outcomes.get(600)).containsEntry("c", ForecastStage.TRIP_START_UNKNOWN);
            softly.assertThat(checked).isGreaterThan(90);
        });
    }

    @Test
    void 창_시작에_걸친_연결이_다음_실정류장에_닿지_않고_끊기면_창_시작_뒤_90분부터_연속_실행과_같게_예보한다() {
        // given
        List<ForecastBatch> batches = new ArrayList<>();
        for (int index = 0; index < 650; index++) {
            List<ForecastObservationRow> rows = new ArrayList<>();
            if (index < 130) {
                rows.add(row("c", passedBeforeVanishing(index), 20));
            }
            if (index >= 200) {
                rows.add(row("b", 1, 30));
            }
            batches.add(confirmed(index + 1, T0.minusSeconds(3600).plusSeconds(10 + 30L * index), rows));
        }
        RouteForecastState continuous = RouteForecastState.fromVerifiedOrigin(ROUTE);
        RouteForecastState replayed = RouteForecastState.replayingFrom(ROUTE, T0);
        Map<Integer, Map<String, String>> outcomes = new HashMap<>();
        int compared = 0;

        // when
        for (int index = 0; index < batches.size(); index++) {
            ForecastStageResult expected = stage.advance(continuous, STATISTICS, "GBIS", batches.get(index));
            continuous = expected.state();
            if (batches.get(index).confirmedAt().isBefore(T0)) {
                continue;
            }
            ForecastStageResult actual = stage.advance(replayed, STATISTICS, "GBIS", batches.get(index));
            replayed = actual.state();
            outcomes.put(index, outcome(actual));
            Map<Long, List<String>> expectedVectors = vectors(expected.snapshots());
            for (Map.Entry<Long, List<String>> entry : vectors(actual.snapshots()).entrySet()) {
                assertThat(entry.getValue()).as("배치 %d 관측 %d", index, entry.getKey())
                    .isEqualTo(expectedVectors.get(entry.getKey()));
                compared++;
            }
        }

        // then
        final int checked = compared;
        assertSoftly(softly -> {
            softly.assertThat(outcomes.get(299)).containsEntry("b", ForecastStage.REPLAY_HISTORY_INCOMPLETE);
            softly.assertThat(outcomes.get(300)).containsEntry("b", "FORECAST");
            softly.assertThat(outcomes.get(649)).containsEntry("b", "FORECAST");
            softly.assertThat(checked).isEqualTo(350);
        });
    }

    private static int passedBeforeVanishing(final int index) {
        if (index < 100) {
            return 2;
        }
        return 3;
    }

    private static int passedBeforeOrAfterWindow(final int index) {
        if (index < 250) {
            return 3;
        }
        return 4;
    }

    private static int passedAcrossWindow(final int index) {
        if (index < 100) {
            return 2;
        }
        if (index < 370) {
            return 3;
        }
        return 4;
    }

    private static Map<Long, List<String>> vectors(List<AlightingAwareFeatureSnapshot> snapshots) {
        Map<Long, List<String>> vectors = new HashMap<>();
        for (AlightingAwareFeatureSnapshot snapshot : snapshots) {
            List<String> targets = new ArrayList<>();
            for (AlightingAwareRouteStatistics.Stop stop : STATISTICS.stops()) {
                try {
                    AlightingAwareFeatureVector vector = snapshot.forTarget(stop.order());
                    targets.add(Arrays.toString(vector.seat88()) + Arrays.toString(vector.probability80()));
                } catch (IllegalArgumentException unsupported) {
                    targets.add("-");
                }
            }
            vectors.put(snapshot.vehicleObservationId(), targets);
        }
        return vectors;
    }

    private static Map<String, String> outcome(ForecastStageResult result) {
        Map<String, String> outcome = new HashMap<>();
        for (ForecastStageResult.WithheldVehicle vehicle : result.withheld()) {
            outcome.put(vehicle.vehicleId(), vehicle.reason());
        }
        for (AlightingAwareFeatureSnapshot snapshot : result.snapshots()) {
            outcome.put(snapshot.source().vehicleId(), "FORECAST");
        }
        return outcome;
    }

    private ForecastObservationRow row(String vehicle, final int passed, final int seats) {
        return new ForecastObservationRow(nextObservation++, vehicle, passed, passed + 1, 1, seats, null, 2);
    }

    private static List<ForecastObservationRow> rows(ForecastObservationRow... rows) {
        return List.of(rows);
    }

    private static ForecastBatch confirmed(final long id, Instant receivedAt, ForecastObservationRow... rows) {
        return confirmed(id, receivedAt, List.of(rows));
    }

    private static ForecastBatch confirmed(final long id, Instant receivedAt, List<ForecastObservationRow> rows) {
        return batch(id, receivedAt, receivedAt.plusSeconds(2), "LIVE", "SUCCESS_ROWS", rows);
    }

    private static ForecastBatch batch(
        final long id,
        Instant receivedAt,
        Instant confirmedAt,
        String origin,
        String outcome,
        List<ForecastObservationRow> rows
    ) {
        return new ForecastBatch(id, VERSION, receivedAt, confirmedAt, origin, outcome, rows.size(), rows);
    }

    private static Map<String, Integer> capacities(List<String> vehicles) {
        Map<String, Integer> capacities = new HashMap<>();
        vehicles.forEach(vehicle -> capacities.put(vehicle, 45));
        return capacities;
    }

    private static AlightingAwareRouteStatistics statistics() {
        List<AlightingAwareRouteStatistics.Stop> stops = new ArrayList<>();
        for (int order = 1; order <= 12; order++) {
            stops.add(new AlightingAwareRouteStatistics.Stop(order, "s" + order, "정류장" + order, order > 10, true));
        }
        Map<String, Map<String, Double>> tables = new HashMap<>();
        for (String table : List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base")) {
            tables.put(table, Map.of());
        }
        return new AlightingAwareRouteStatistics(ROUTE, stops, 2, tables, Map.of(), Set.of(), true);
    }
}
