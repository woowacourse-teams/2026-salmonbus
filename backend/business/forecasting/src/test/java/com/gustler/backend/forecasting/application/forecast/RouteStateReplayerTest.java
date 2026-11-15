package com.gustler.backend.forecasting.application.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 되감기 창을 늘리는 규칙과, 예보 단계를 돌리지 못하고 끝난 배치의 통과를 되감으면서 찾는 규칙을 메모리 원본으로 본다.
 * 배치는 1분 간격이고 차량은 정류장 순번 1~9(상행)만 돈다. 순번이 줄면 새 운행이다.
 */
class RouteStateReplayerTest {
    private static final String ROUTE = "replayer-route";
    private static final long VERSION = 7;
    private static final long ROUTE_ID = 31;
    private static final LocalDate SOURCE_DATE = LocalDate.of(2026, 10, 14);
    private static final Instant T0 = Instant.parse("2026-10-13T20:00:00Z");
    private static final AlightingAwareRouteStatistics STATISTICS = statistics();
    private final ForecastStage stage = new ForecastStage((source, vehicles, at, batch) -> capacities(vehicles));
    private long nextObservation = 1000;

    @Test
    void 여섯_시간보다_긴_운행_중에_다시_뜨면_창을_늘려_되감아_그_차량을_빼지_않고_입력이_계속_돈_상태와_같다() {
        // given
        List<ForecastBatch> batches = stream(840, 300, 810);
        MemorySource source = new MemorySource(batches);
        RouteStateReplayer replayer = new RouteStateReplayer(source, stage, Duration.ofHours(6), Duration.ofHours(12));
        final int split = 780;
        RouteForecastState continuous = continuousThrough(batches, split);
        ForecastBatch last = batches.get(split);

        // when
        RouteStateReplayer.Result replayed = replayer.replay(STATISTICS, last.confirmedAt(), last.observationBatchId());

        // then
        RouteForecastState resumed = replayed.state();
        int compared = 0;
        Set<String> forecast = new HashSet<>();
        Set<String> withheld = new HashSet<>();
        for (int index = split + 1; index < split + 25; index++) {
            ForecastStageResult expected = stage.advance(continuous, STATISTICS, "GBIS", batches.get(index));
            ForecastStageResult actual = stage.advance(resumed, STATISTICS, "GBIS", batches.get(index));
            Map<Long, List<String>> expectedVectors = vectors(expected.snapshots());
            for (Map.Entry<Long, List<String>> entry : vectors(actual.snapshots()).entrySet()) {
                assertThat(entry.getValue()).as("배치 %d 관측 %d", index, entry.getKey())
                    .isEqualTo(expectedVectors.get(entry.getKey()));
                compared++;
            }
            actual.snapshots().forEach(snapshot -> forecast.add(snapshot.source().vehicleId()));
            actual.withheld().forEach(vehicle -> withheld.add(vehicle.vehicleId()));
            continuous = expected.state();
            resumed = actual.state();
        }
        final int checked = compared;
        assertSoftly(softly -> {
            softly.assertThat(replayed.extension()).isEqualTo(RouteStateReplayer.Extension.RESOLVED);
            softly.assertThat(source.windows).containsExactly(Duration.ofHours(6), Duration.ofHours(12));
            softly.assertThat(replayed.state().replayFrom()).contains(last.confirmedAt().minus(Duration.ofHours(12)));
            softly.assertThat(replayed.state().tripStartKnown("long")).isTrue();
            softly.assertThat(forecast).contains("long", "b", "c");
            softly.assertThat(withheld).isEmpty();
            softly.assertThat(checked).isGreaterThan(60);
        });
    }

    @Test
    void 창을_늘리지_않으면_여섯_시간보다_긴_운행의_차량은_다음_운행까지_예보에서_빠진다() {
        // given
        List<ForecastBatch> batches = stream(840, 300, 810);
        MemorySource source = new MemorySource(batches);
        RouteStateReplayer replayer = new RouteStateReplayer(source, stage, Duration.ofHours(6), Duration.ofHours(6));
        ForecastBatch last = batches.get(780);

        // when
        RouteStateReplayer.Result replayed = replayer.replay(STATISTICS, last.confirmedAt(), last.observationBatchId());
        ForecastStageResult next = stage.advance(replayed.state(), STATISTICS, "GBIS", batches.get(781));

        // then
        assertSoftly(softly -> {
            softly.assertThat(replayed.extension()).isEqualTo(RouteStateReplayer.Extension.NONE);
            softly.assertThat(source.windows).containsExactly(Duration.ofHours(6));
            softly.assertThat(next.withheld()).extracting(ForecastStageResult.WithheldVehicle::vehicleId)
                .contains("long");
        });
    }

    @Test
    void 여섯_시간으로_충분하면_두_번째_되감기를_돌리지_않는다() {
        // given
        List<ForecastBatch> batches = stream(480, 300, 480);
        MemorySource source = new MemorySource(batches);
        RouteStateReplayer replayer = new RouteStateReplayer(source, stage, Duration.ofHours(6), Duration.ofHours(12));
        ForecastBatch last = batches.get(450);

        // when
        RouteStateReplayer.Result replayed = replayer.replay(STATISTICS, last.confirmedAt(), last.observationBatchId());

        // then
        assertSoftly(softly -> {
            softly.assertThat(replayed.extension()).isEqualTo(RouteStateReplayer.Extension.NONE);
            softly.assertThat(source.windows).containsExactly(Duration.ofHours(6));
            softly.assertThat(replayed.state().replayShortfallAt(last.confirmedAt()).none()).isTrue();
            softly.assertThat(replayed.state().replayFrom()).contains(last.confirmedAt().minus(Duration.ofHours(6)));
        });
    }

    @Test
    void 늘린_창으로도_운행_시작이_보이지_않으면_그_차량은_빠진_채로_남는다() {
        // given
        List<ForecastBatch> batches = stream(480, 0, 480);
        MemorySource source = new MemorySource(batches);
        RouteStateReplayer replayer = new RouteStateReplayer(source, stage, Duration.ofHours(3), Duration.ofHours(6));
        ForecastBatch last = batches.get(450);

        // when
        RouteStateReplayer.Result replayed = replayer.replay(STATISTICS, last.confirmedAt(), last.observationBatchId());
        ForecastStageResult next = stage.advance(replayed.state(), STATISTICS, "GBIS", batches.get(451));

        // then
        assertSoftly(softly -> {
            softly.assertThat(replayed.succeeded()).isTrue();
            softly.assertThat(replayed.extension()).isEqualTo(RouteStateReplayer.Extension.UNRESOLVED);
            softly.assertThat(source.windows).containsExactly(Duration.ofHours(3), Duration.ofHours(6));
            softly.assertThat(replayed.state().tripStartKnown("long")).isFalse();
            softly.assertThat(next.withheld()).extracting(ForecastStageResult.WithheldVehicle::vehicleId)
                .contains("long");
        });
    }

    @Test
    void 늘린_창을_읽지_못하면_처음_되감은_상태를_쓴다() {
        // given
        List<ForecastBatch> batches = stream(480, 0, 480);
        MemorySource source = new MemorySource(batches);
        source.failBeyond = Duration.ofHours(3);
        RouteStateReplayer replayer = new RouteStateReplayer(source, stage, Duration.ofHours(3), Duration.ofHours(6));
        ForecastBatch last = batches.get(450);

        // when
        RouteStateReplayer.Result replayed = replayer.replay(STATISTICS, last.confirmedAt(), last.observationBatchId());

        // then
        assertSoftly(softly -> {
            softly.assertThat(replayed.succeeded()).isTrue();
            softly.assertThat(replayed.extension()).isEqualTo(RouteStateReplayer.Extension.FAILED);
            softly.assertThat(replayed.state().replayFrom()).contains(last.confirmedAt().minus(Duration.ofHours(3)));
            softly.assertThat(replayed.state().cursor().orElseThrow().observationBatchId())
                .isEqualTo(last.observationBatchId());
        });
    }

    @Test
    void 예보_단계를_돌리지_못하고_끝난_배치가_닫은_통과를_되감으면서_계속_돈_상태와_같은_값으로_돌려준다() {
        // given
        List<ForecastBatch> batches = stream(480, 300, 480);
        final int holeStart = 400;
        final int holeEnd = 430;
        List<PassClosed> expected = new ArrayList<>();
        RouteForecastState continuous = RouteForecastState.fromVerifiedOrigin(ROUTE);
        for (int index = 0; index < holeEnd; index++) {
            ForecastStageResult result = stage.advance(continuous, STATISTICS, "GBIS", batches.get(index));
            if (index >= holeStart) {
                result.closedPasses().forEach(pass -> expected.add(pass.toEvent(SOURCE_DATE, ROUTE_ID)));
            }
            continuous = result.state();
        }
        MemorySource source = new MemorySource(batches);
        for (int index = holeStart; index < holeEnd; index++) {
            source.unforecast.put(batches.get(index).observationBatchId(),
                new ReplaySource.UnforecastRun(SOURCE_DATE, ROUTE_ID));
        }
        RouteStateReplayer replayer = new RouteStateReplayer(source, stage, Duration.ofHours(6), Duration.ofHours(12));
        ForecastBatch last = batches.get(holeEnd - 1);

        // when
        RouteStateReplayer.Result replayed = replayer.replay(STATISTICS, last.confirmedAt(), last.observationBatchId());

        // then
        assertSoftly(softly -> {
            softly.assertThat(expected).extracting(PassClosed::vehicleId).contains("long", "b", "c");
            softly.assertThat(replayed.recoveredPasses()).containsExactlyElementsOf(expected);
        });
    }

    @Test
    void 연결_시작은_보았지만_운행_시작을_보지_못한_차량의_통과는_되찾지_않는다() {
        // given
        List<ForecastBatch> batches = streamWithChainBreak();
        final int holeStart = 395;
        final int holeEnd = 446;
        List<PassClosed> continuous = new ArrayList<>();
        RouteForecastState state = RouteForecastState.fromVerifiedOrigin(ROUTE);
        for (int index = 0; index < holeEnd; index++) {
            ForecastStageResult result = stage.advance(state, STATISTICS, "GBIS", batches.get(index));
            if (index >= holeStart) {
                result.closedPasses().forEach(pass -> continuous.add(pass.toEvent(SOURCE_DATE, ROUTE_ID)));
            }
            state = result.state();
        }
        MemorySource source = new MemorySource(batches);
        for (int index = holeStart; index < holeEnd; index++) {
            source.unforecast.put(batches.get(index).observationBatchId(),
                new ReplaySource.UnforecastRun(SOURCE_DATE, ROUTE_ID));
        }
        ForecastBatch last = batches.get(holeEnd - 1);

        // when
        RouteStateReplayer.Result shortWindow = new RouteStateReplayer(source, stage, Duration.ofHours(3),
            Duration.ofHours(3)).replay(STATISTICS, last.confirmedAt(), last.observationBatchId());
        RouteStateReplayer.Result longWindow = new RouteStateReplayer(source, stage, Duration.ofHours(6),
            Duration.ofHours(6)).replay(STATISTICS, last.confirmedAt(), last.observationBatchId());

        // then
        List<PassClosed> withoutBroken = continuous.stream().filter(pass -> !"g".equals(pass.vehicleId())).toList();
        assertSoftly(softly -> {
            softly.assertThat(continuous).extracting(PassClosed::vehicleId).contains("g", "b", "c");
            softly.assertThat(shortWindow.state().vehicles().get("g").chainStartKnown()).isTrue();
            softly.assertThat(shortWindow.state().tripStartKnown("g")).isFalse();
            softly.assertThat(shortWindow.recoveredPasses()).containsExactlyElementsOf(withoutBroken);
            softly.assertThat(longWindow.recoveredPasses()).containsExactlyElementsOf(continuous);
        });
    }

    /** 검증된 출발 지점부터 {@code through}번째 배치까지 반영한 상태다. */
    private RouteForecastState continuousThrough(List<ForecastBatch> batches, final int through) {
        RouteForecastState state = RouteForecastState.fromVerifiedOrigin(ROUTE);
        for (int index = 0; index <= through; index++) {
            state = stage.replay(state, STATISTICS, batches.get(index)).state();
        }
        return state;
    }

    /**
     * 1분 간격 배치 {@code minutes}개를 만든다. 차량 {@code long}은 {@code longFrom}분부터 {@code longUntil}분 전까지 한 운행을
     * 끊김 없이 이어 가고(약 64분에 한 정류장), 차량 {@code b}·{@code c}는 90분짜리 운행을 되풀이한다.
     */
    private List<ForecastBatch> stream(final int minutes, final int longFrom, final int longUntil) {
        List<ForecastBatch> batches = new ArrayList<>();
        for (int minute = 0; minute < minutes; minute++) {
            List<ForecastObservationRow> rows = new ArrayList<>();
            rows.add(row("b", 1 + (minute % 90) / 10, 30));
            rows.add(row("c", 1 + ((minute + 45) % 90) / 10, 25));
            if (minute >= longFrom && minute < longUntil) {
                rows.add(row("long", 1 + (minute - longFrom) / 64, 20));
            }
            Instant receivedAt = T0.plusSeconds(60L * minute);
            batches.add(new ForecastBatch(minute + 1, VERSION, receivedAt, receivedAt.plusSeconds(2), "LIVE",
                "SUCCESS_ROWS", rows.size(), rows));
        }
        return batches;
    }

    /**
     * 차량 {@code g}가 200분에 운행을 시작해 40분에 한 정류장씩 가다가 270~273분에 보이지 않는 흐름이다. 274분에 연결은 새로
     * 시작하지만(90초 초과) 운행은 이어진다(30분 이내).
     */
    private List<ForecastBatch> streamWithChainBreak() {
        List<ForecastBatch> batches = new ArrayList<>();
        for (int minute = 0; minute < 480; minute++) {
            List<ForecastObservationRow> rows = new ArrayList<>();
            rows.add(row("b", 1 + (minute % 90) / 10, 30));
            rows.add(row("c", 1 + ((minute + 45) % 90) / 10, 25));
            if (minute >= 200 && (minute < 270 || minute > 273)) {
                rows.add(row("g", 1 + (minute - 200) / 40, 20));
            }
            Instant receivedAt = T0.plusSeconds(60L * minute);
            batches.add(new ForecastBatch(minute + 1, VERSION, receivedAt, receivedAt.plusSeconds(2), "LIVE",
                "SUCCESS_ROWS", rows.size(), rows));
        }
        return batches;
    }

    private ForecastObservationRow row(String vehicle, final int passed, final int seats) {
        return new ForecastObservationRow(nextObservation++, vehicle, passed, passed + 1, 1, seats, null, 2);
    }

    private static Map<Long, List<String>> vectors(List<AlightingAwareFeatureSnapshot> snapshots) {
        Map<Long, List<String>> vectors = new HashMap<>();
        for (AlightingAwareFeatureSnapshot snapshot : snapshots) {
            List<String> targets = new ArrayList<>();
            for (AlightingAwareRouteStatistics.Stop stop : STATISTICS.stops()) {
                targets.add(vector(snapshot, stop.order()));
            }
            vectors.put(snapshot.vehicleObservationId(), targets);
        }
        return vectors;
    }

    private static String vector(AlightingAwareFeatureSnapshot snapshot, final int target) {
        try {
            AlightingAwareFeatureVector vector = snapshot.forTarget(target);
            return Arrays.toString(vector.seat88()) + Arrays.toString(vector.probability80());
        } catch (IllegalArgumentException unsupported) {
            return "-";
        }
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

    /** 실제 되감기 원본과 같은 범위 규칙으로 메모리의 배치를 돌려준다. 읽은 창의 길이를 차례로 적어 둔다. */
    private static final class MemorySource implements ReplaySource {
        private final List<ForecastBatch> batches;
        private final List<Duration> windows = new ArrayList<>();
        private final Map<Long, ReplaySource.UnforecastRun> unforecast = new HashMap<>();
        private Duration failBeyond;

        private MemorySource(List<ForecastBatch> batches) {
            this.batches = batches;
        }

        @Override
        public ReplaySource.Read read(
            AlightingAwareRouteStatistics statistics,
            Instant from,
            Instant through,
            final long throughBatchId
        ) {
            Duration window = Duration.between(from, through);
            windows.add(window);
            if (failBeyond != null && window.compareTo(failBeyond) > 0) {
                return ReplaySource.Read.failed("TEST_ROW_LIMIT");
            }
            List<ForecastBatch> selected = batches.stream()
                .filter(batch -> !batch.confirmedAt().isBefore(from))
                .filter(batch -> isThrough(batch, through, throughBatchId))
                .toList();
            Map<Long, ReplaySource.UnforecastRun> runs = new HashMap<>();
            selected.forEach(batch -> {
                ReplaySource.UnforecastRun run = unforecast.get(batch.observationBatchId());
                if (run != null) {
                    runs.put(batch.observationBatchId(), run);
                }
            });
            return ReplaySource.Read.of(selected, runs);
        }

        private static boolean isThrough(ForecastBatch batch, Instant through, final long throughBatchId) {
            if (batch.confirmedAt().isBefore(through)) {
                return true;
            }
            return batch.confirmedAt().equals(through) && batch.observationBatchId() <= throughBatchId;
        }
    }
}
