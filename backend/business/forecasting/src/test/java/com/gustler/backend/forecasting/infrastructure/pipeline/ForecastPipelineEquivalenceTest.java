package com.gustler.backend.forecasting.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.application.pipeline.ClosedPass;
import com.gustler.backend.forecasting.application.pipeline.ForecastBatch;
import com.gustler.backend.forecasting.application.pipeline.ForecastStage;
import com.gustler.backend.forecasting.application.pipeline.ForecastStageResult;
import com.gustler.backend.forecasting.application.pipeline.RouteForecastState;
import com.gustler.backend.forecasting.application.pipeline.RouteStateReplayer;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예보 단계가 관측 흐름을 한 배치씩 받아 만드는 입력·노선 상태·닫힌 통과를 고정 기대값과 견준다. 기대값은 기준 구현
 * (dev {@code e98ae3ea}의 입력 조회)이 같은 흐름에 낸 값의 지문이다({@link ForecastInputBaseline}).
 */
@ForecastingIntegrationTest
@Transactional
class ForecastPipelineEquivalenceTest {
    private static final Instant EVENING = Instant.parse("2026-10-14T10:00:00Z");
    private static final Set<String> REPLAY_EXCLUSIONS = Set.of(ForecastStage.TRIP_START_UNKNOWN,
        ForecastStage.REPLAY_HISTORY_INCOMPLETE);

    @Autowired
    JdbcClient jdbc;
    private VehicleCapacityCache cache;

    @BeforeEach
    void setup() {
        cache = new VehicleCapacityCache(new JdbcVehicleCapacityStore(jdbc));
    }

    @Test
    void 연속_실행에서_입력과_통과와_상태가_배치마다_기준_기록과_같다() {
        // given
        ObservationStreamFixture.Stream stream = ObservationStreamFixture.insert(jdbc, EVENING, Duration.ofHours(6),
            Duration.ofSeconds(30), EVENING.plus(Duration.ofHours(3)), 20261115L);
        StreamKeys keys = StreamKeys.of(jdbc, stream);
        ForecastInputDigests digests = new ForecastInputDigests(keys);
        ForecastInputBaseline baseline = ForecastInputBaseline.read("forecast-input/continuous-20261115");
        ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(cache));
        AlightingAwareRouteStatistics statistics = stream.statistics();
        RouteForecastState state = RouteForecastState.fromVerifiedOrigin(stream.route());
        Map<String, AlightingAwareObservation> lastByVehicle = new HashMap<>();
        Map<Long, Long> chainStartByObservation = new HashMap<>();
        Map<String, List<AlightingAwareObservation>> sequence = new HashMap<>();
        Tally tally = new Tally();

        // when
        for (final long batchId : stream.batches()) {
            ForecastBatch batch = ObservationStreamFixture.read(jdbc, batchId);
            ForecastInputBaseline.Batch expected = baseline.batch(keys.batch(batchId));
            ForecastStageResult actual = stage.advance(state, statistics, ObservationStreamFixture.SOURCE_ID, batch);
            if (!batch.confirmed()) {
                assertThat(expected.kind()).isEqualTo(ForecastInputBaseline.Kind.UNCONFIRMED);
                assertThat(expected.reason()).isEqualTo("ALIGHTING_AWARE_SOURCE_BATCH_MISSING");
                assertThat(actual.status()).isEqualTo(ForecastStageResult.Status.NOT_CONFIRMED);
                assertThat(actual.state()).isSameAs(state);
                tally.skipped++;
                continue;
            }
            if (expected.kind() == ForecastInputBaseline.Kind.FAILED) {
                assertThat(actual.snapshots()).as("입력을 만들지 못한 배치 %d: %s", batchId, expected.reason()).isEmpty();
                tally.failed++;
                state = actual.state();
                continue;
            }
            assertThat(expected.kind()).isEqualTo(ForecastInputBaseline.Kind.FORECAST);
            assertThat(actual.status()).isEqualTo(ForecastStageResult.Status.FORECAST);
            tally.vectors += assertSameSnapshots(batchId, expected, actual.snapshots(), statistics, keys, digests);
            tally.batches++;
            tally.snapshots += expected.snapshots().size();
            assertSameState(batchId, expected, actual.state(), digests);
            record(actual.state(), batchId, lastByVehicle, chainStartByObservation, sequence);
            List<AlightingAwarePassEvent> closed = actual.closedPasses().stream().map(ClosedPass::event).toList();
            assertThat(closed).as("배치 %d가 닫은 통과 수", batchId).hasSize(expected.passCount());
            assertThat(digests.passes(closed)).as("배치 %d의 닫힌 통과", batchId).isEqualTo(expected.passDigest());
            assertThat(actual.unattributedPasses()).isZero();
            for (ClosedPass pass : actual.closedPasses()) {
                assertClosedPass(pass, chainStartByObservation, sequence);
                tally.passes++;
            }
            state = actual.state();
        }

        // then
        Map<String, Long> coverage = coverage(stream);
        assertSoftly(softly -> {
            softly.assertThat(coverage).allSatisfy((scenario, count) -> assertThat(count).as(scenario).isPositive());
            softly.assertThat(tally.batches).isGreaterThan(600);
            softly.assertThat(tally.skipped).isGreaterThan(20);
            softly.assertThat(tally.snapshots).isGreaterThan(2_500);
            softly.assertThat(tally.vectors).isGreaterThan(9_000);
            softly.assertThat(tally.passes).isGreaterThan(100);
            softly.assertThat(tally.failed).isZero();
        });
    }

    @Test
    void 출퇴근_범위_밖_배치를_건너뛰어도_범위_안의_입력이_기준_기록과_같다() {
        // given
        Instant dawn = Instant.parse("2026-10-15T20:00:00Z");
        ObservationStreamFixture.Stream stream = ObservationStreamFixture.insert(jdbc, dawn, Duration.ofMinutes(120),
            Duration.ofSeconds(30), null, 7L);
        StreamKeys keys = StreamKeys.of(jdbc, stream);
        ForecastInputDigests digests = new ForecastInputDigests(keys);
        ForecastInputBaseline baseline = ForecastInputBaseline.read("forecast-input/rush-only-7");
        ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(cache));
        RouteForecastState state = RouteForecastState.fromVerifiedOrigin(stream.route());
        int outside = 0;
        int compared = 0;
        int vectors = 0;

        // when
        for (final long batchId : stream.batches()) {
            ForecastBatch batch = ObservationStreamFixture.read(jdbc, batchId);
            ForecastStageResult actual =
                stage.advance(state, stream.statistics(), ObservationStreamFixture.SOURCE_ID, batch);
            if (!batch.confirmed()) {
                continue;
            }
            ForecastInputBaseline.Batch expected = baseline.batch(keys.batch(batchId));
            if (expected.kind() == ForecastInputBaseline.Kind.FORECAST) {
                vectors += assertSameSnapshots(batchId, expected, actual.snapshots(), stream.statistics(), keys,
                    digests);
                compared++;
            }
            if (expected.kind() != ForecastInputBaseline.Kind.FORECAST) {
                assertThat(expected.kind()).isEqualTo(ForecastInputBaseline.Kind.OUT_OF_SCOPE);
                outside++;
            }
            state = actual.state();
        }

        // then
        final int outsideBatches = outside;
        final int comparedBatches = compared;
        final int comparedVectors = vectors;
        assertSoftly(softly -> {
            softly.assertThat(outsideBatches).isGreaterThan(80);
            softly.assertThat(comparedBatches).isGreaterThan(80);
            softly.assertThat(comparedVectors).isGreaterThan(1_000);
        });
    }

    @Test
    void 되감은_뒤_이어_가도_예보한_차량의_입력은_연속_실행과_같고_창이_짧으면_빠지는_차량만_는다() {
        // given
        ObservationStreamFixture.Stream stream = ObservationStreamFixture.insert(jdbc, EVENING, Duration.ofHours(6),
            Duration.ofSeconds(30), EVENING.plus(Duration.ofHours(3)), 20261115L);
        ForecastStage stage = new ForecastStage(new CachedVehicleCapacities(cache));
        AlightingAwareRouteStatistics statistics = stream.statistics();
        List<ForecastBatch> batches = stream.batches().stream()
            .map(id -> ObservationStreamFixture.read(jdbc, id))
            .toList();
        List<Map<Long, String>> continuous = new ArrayList<>();
        List<Map<Long, String>> continuousWithheld = new ArrayList<>();
        RouteForecastState state = RouteForecastState.fromVerifiedOrigin(stream.route());
        for (ForecastBatch batch : batches) {
            ForecastStageResult result = stage.advance(state, statistics, ObservationStreamFixture.SOURCE_ID, batch);
            continuous.add(digests(result.snapshots(), statistics));
            continuousWithheld.add(withheld(result));
            state = result.state();
        }
        List<Integer> confirmed = new ArrayList<>();
        for (int index = 0; index < batches.size(); index++) {
            if (batches.get(index).confirmed()) {
                confirmed.add(index);
            }
        }
        JdbcReplaySource source = new JdbcReplaySource(jdbc, JdbcReplaySource.DEFAULT_MAXIMUM_ROWS);
        List<Duration> windows = List.of(Duration.ofHours(6), Duration.ofHours(3), Duration.ofMinutes(140),
            Duration.ofMinutes(100), Duration.ofMinutes(45), Duration.ofMinutes(15));
        int compared = 0;
        Map<String, Integer> exclusions = new HashMap<>();

        // when
        for (final int split : List.of(confirmed.get(300), confirmed.get(520))) {
            ForecastBatch last = batches.get(split);
            for (Duration window : windows) {
                RouteStateReplayer.Result replayed = new RouteStateReplayer(source, stage, window, window)
                    .replay(statistics, last.confirmedAt(), last.observationBatchId());
                assertThat(replayed.succeeded()).as(replayed.failureReason()).isTrue();
                assertThat(replayed.state().cursor().orElseThrow().observationBatchId())
                    .isEqualTo(last.observationBatchId());
                RouteForecastState resumed = replayed.state();
                int same = 0;
                int excluded = 0;
                Map<String, Integer> byReason = new HashMap<>();
                for (int index = split + 1; index < Math.min(batches.size(), split + 181); index++) {
                    ForecastStageResult result = stage.advance(resumed, statistics, ObservationStreamFixture.SOURCE_ID,
                        batches.get(index));
                    Map<Long, String> actual = digests(result.snapshots(), statistics);
                    Map<Long, String> reasons = withheld(result);
                    for (Map.Entry<Long, String> entry : actual.entrySet()) {
                        assertThat(entry.getValue()).as("창 %s, 나눈 지점 %d, 배치 %d, 관측 %d", window, split,
                            batches.get(index).observationBatchId(), entry.getKey())
                            .isEqualTo(continuous.get(index).get(entry.getKey()));
                        same++;
                    }
                    for (Long observation : continuous.get(index).keySet()) {
                        if (!actual.containsKey(observation)) {
                            assertThat(reasons.get(observation)).isIn(REPLAY_EXCLUSIONS);
                            byReason.merge(reasons.get(observation), 1, Integer::sum);
                            excluded++;
                        }
                    }
                    for (Map.Entry<Long, String> reason : reasons.entrySet()) {
                        if (!REPLAY_EXCLUSIONS.contains(reason.getValue())) {
                            assertThat(continuousWithheld.get(index)).containsEntry(reason.getKey(), reason.getValue());
                        }
                    }
                    resumed = result.state();
                }
                if (window.equals(Duration.ofHours(6))) {
                    assertThat(excluded).isZero();
                }
                if (window.compareTo(Duration.ofMinutes(100)) < 0) {
                    assertThat(excluded).isPositive();
                }
                compared += same;
                byReason.forEach((reason, count) -> exclusions.merge(reason, count, Integer::sum));
            }
        }

        // then
        final int comparedVectors = compared;
        assertSoftly(softly -> {
            softly.assertThat(comparedVectors).isGreaterThan(2_000);
            softly.assertThat(exclusions)
                .containsKeys(ForecastStage.TRIP_START_UNKNOWN, ForecastStage.REPLAY_HISTORY_INCOMPLETE);
        });
    }

    @Test
    void 되감기_창에_줄_수가_다른_배치가_있으면_그_노선의_되감기는_실패한다() {
        // given
        ObservationStreamFixture.Stream stream = ObservationStreamFixture.insert(jdbc, EVENING, Duration.ofMinutes(20),
            Duration.ofSeconds(30), null, 11L);
        ForecastBatch last = lastConfirmed(stream);
        ForecastBatch broken = stream.batches().stream().map(id -> ObservationStreamFixture.read(jdbc, id))
            .filter(ForecastBatch::confirmed).toList().get(5);
        jdbc.sql("UPDATE observation_batch SET stored_rows=stored_rows+1 WHERE id=?")
            .param(broken.observationBatchId())
            .update();
        RouteStateReplayer replayer = new RouteStateReplayer(
            new JdbcReplaySource(jdbc, JdbcReplaySource.DEFAULT_MAXIMUM_ROWS),
            new ForecastStage(new CachedVehicleCapacities(cache)), Duration.ofHours(6), Duration.ofHours(6));

        // when
        RouteStateReplayer.Result result =
            replayer.replay(stream.statistics(), last.confirmedAt(), last.observationBatchId());

        // then
        assertSoftly(softly -> {
            softly.assertThat(result.succeeded()).isFalse();
            softly.assertThat(result.failureReason()).isEqualTo(ForecastStage.SOURCE_ROWS_MISSING);
        });
    }

    @Test
    void 되감기_창의_판본_정류장이_통계와_다르면_실패한다() {
        // given
        ObservationStreamFixture.Stream stream = ObservationStreamFixture.insert(jdbc, EVENING, Duration.ofMinutes(20),
            Duration.ofSeconds(30), null, 13L);
        ForecastBatch last = lastConfirmed(stream);
        jdbc.sql(
            "UPDATE route_stop SET boarding_allowed=NOT boarding_allowed WHERE route_version_id=? AND stop_order=2")
            .param(last.routeVersionId()).update();
        RouteStateReplayer replayer = new RouteStateReplayer(
            new JdbcReplaySource(jdbc, JdbcReplaySource.DEFAULT_MAXIMUM_ROWS),
            new ForecastStage(new CachedVehicleCapacities(cache)), Duration.ofHours(6), Duration.ofHours(6));

        // when
        RouteStateReplayer.Result result =
            replayer.replay(stream.statistics(), last.confirmedAt(), last.observationBatchId());

        // then
        assertThat(result.failureReason())
            .isEqualTo("ALIGHTING_AWARE_INCOMPATIBLE_HISTORICAL_ROUTE_VERSION:" + last.routeVersionId());
    }

    private Map<String, Long> coverage(ObservationStreamFixture.Stream stream) {
        String confirmed = """
            b.ingestion_origin='LIVE' AND b.outcome='SUCCESS_ROWS' AND b.stored_rows>0
            AND b.input_confirmed_at BETWEEN b.response_received_at AND b.response_received_at+interval '90 seconds'
            """;
        List<Long> versions = List.copyOf(stream.stopsByVersion().keySet());
        Map<String, String> scenarios = Map.of(
            "같은 배치 같은 차량", "SELECT count(*) FROM (SELECT 1 FROM vehicle_observation o JOIN "
                + "observation_batch b ON b.id=o.observation_batch_id WHERE b.route_version_id IN "
                + "(:versions) AND o.vehicle_id IS NOT NULL AND " + confirmed
                + " GROUP BY b.id,o.vehicle_id HAVING count(*)>1) duplicate",
            "차량 번호 없는 줄", "SELECT count(*) FROM vehicle_observation o JOIN observation_batch b "
                + "ON b.id=o.observation_batch_id WHERE b.route_version_id IN (:versions) AND o.vehicle_id "
                + "IS NULL AND " + confirmed,
            "좌석 모름", "SELECT count(*) FROM vehicle_observation o JOIN observation_batch b "
                + "ON b.id=o.observation_batch_id WHERE b.route_version_id IN (:versions) AND "
                + "o.seat_unknown_reason IS NOT NULL AND " + confirmed,
            "범위 밖 좌석", "SELECT count(*) FROM vehicle_observation o JOIN observation_batch b "
                + "ON b.id=o.observation_batch_id WHERE b.route_version_id IN (:versions) AND "
                + "o.remaining_seats>70 AND " + confirmed,
            "90초 넘긴 착수", "SELECT count(*) FROM observation_batch b WHERE b.route_version_id IN "
                + "(:versions) AND b.input_confirmed_at>b.response_received_at+interval '90 seconds'",
            "빈 배치", "SELECT count(*) FROM observation_batch b WHERE b.route_version_id IN "
                + "(:versions) AND b.outcome='SUCCESS_EMPTY'",
            "판본 전환", "SELECT count(DISTINCT b.route_version_id)-1 FROM observation_batch b "
                + "WHERE b.route_version_id IN (:versions)",
            "자정 넘김", "SELECT count(DISTINCT (b.input_confirmed_at AT TIME ZONE "
                + "'Asia/Seoul')::date)-1 FROM observation_batch b WHERE b.route_version_id IN (:versions)");
        Map<String, Long> counts = new HashMap<>();
        scenarios.forEach((scenario, sql) -> counts.put(scenario,
            jdbc.sql(sql).param("versions", versions).query(Long.class).single()));
        return counts;
    }

    private ForecastBatch lastConfirmed(ObservationStreamFixture.Stream stream) {
        return stream.batches().stream().map(id -> ObservationStreamFixture.read(jdbc, id))
            .filter(ForecastBatch::confirmed).reduce((first, second) -> second).orElseThrow();
    }

    /** 예보한 차량이 기대값과 같고 차량마다 기준 관측·정원·운행 시작·입력 벡터의 지문이 같은지 본다. 견준 벡터 수를 돌려준다. */
    private static int assertSameSnapshots(
        final long batchId,
        ForecastInputBaseline.Batch expected,
        List<AlightingAwareFeatureSnapshot> actual,
        AlightingAwareRouteStatistics statistics,
        StreamKeys keys,
        ForecastInputDigests digests
    ) {
        Map<String, AlightingAwareFeatureSnapshot> byObservation = new HashMap<>();
        actual.forEach(snapshot -> byObservation.put(keys.observation(snapshot.vehicleObservationId()), snapshot));
        assertThat(byObservation.keySet()).as("배치 %d의 예보 차량", batchId).isEqualTo(expected.snapshots().keySet());
        int vectors = 0;
        for (Map.Entry<String, String> entry : expected.snapshots().entrySet()) {
            AlightingAwareFeatureSnapshot snapshot = byObservation.get(entry.getKey());
            assertThat(digests.snapshot(snapshot, statistics)).as("배치 %d 관측 %s의 입력", batchId, entry.getKey())
                .isEqualTo(entry.getValue());
            for (AlightingAwareRouteStatistics.Stop stop : statistics.stops()) {
                if (vector(snapshot, stop.order()) != null) {
                    vectors++;
                }
            }
        }
        return vectors;
    }

    private static AlightingAwareFeatureVector vector(AlightingAwareFeatureSnapshot snapshot, final int target) {
        return ForecastInputDigests.vectorOrNone(snapshot, target);
    }

    private static void assertSameState(
        final long batchId,
        ForecastInputBaseline.Batch expected,
        RouteForecastState state,
        ForecastInputDigests digests
    ) {
        final long rank = state.cursor().orElseThrow().rank();
        assertThat(rank).isEqualTo(expected.rank());
        assertThat(digests.state(rank, state.observations(), state.prefix(), state.events()))
            .as("배치 %d의 보존 관측·누적 상태·통과 사건", batchId).isEqualTo(expected.stateDigest());
    }

    private static void record(
        RouteForecastState state,
        final long batchId,
        Map<String, AlightingAwareObservation> lastByVehicle,
        Map<Long, Long> chainStartByObservation,
        Map<String, List<AlightingAwareObservation>> sequence
    ) {
        state.vehicles().values().stream()
            .map(RouteForecastState.VehicleTrack::last)
            .filter(observation -> observation.batchId() == batchId)
            .forEach(observation -> {
                AlightingAwareObservation previous = lastByVehicle.put(observation.vehicleId(), observation);
                final long start = chainStart(observation, previous, chainStartByObservation);
                chainStartByObservation.put(observation.observationId(), start);
                sequence.computeIfAbsent(observation.vehicleId(), ignored -> new ArrayList<>()).add(observation);
            });
    }

    private static long chainStart(
        AlightingAwareObservation observation,
        AlightingAwareObservation previous,
        Map<Long, Long> chainStartByObservation
    ) {
        if (observation.startsConfirmedChainAfter(previous)) {
            return observation.observationId();
        }
        return chainStartByObservation.get(previous.observationId());
    }

    private static void assertClosedPass(
        ClosedPass pass,
        Map<Long, Long> chainStartByObservation,
        Map<String, List<AlightingAwareObservation>> sequence
    ) {
        AlightingAwarePassEvent event = pass.event();
        assertThat(pass.chainStartObservationId())
            .isEqualTo(chainStartByObservation.get(event.confirmation().observationId()))
            .isEqualTo(chainStartByObservation.get(event.arrival().observationId()));
        Instant reached = null;
        for (AlightingAwareObservation observation : sequence.get(event.vehicleId())) {
            if (hasKnownSeatWithinPass(observation, event)) {
                reached = observation.receivedAt();
            }
        }
        assertThat(pass.reachedKnownSeatAt()).isEqualTo(reached);
    }

    private static boolean hasKnownSeatWithinPass(
        AlightingAwareObservation observation,
        AlightingAwarePassEvent event
    ) {
        return observation.passedStopOrder() == event.stopOrder() && observation.seats() != null
            && !observation.receivedAt().isBefore(event.arrival().receivedAt())
            && !observation.receivedAt().isAfter(event.confirmation().receivedAt());
    }

    private static Map<Long, String> digests(
        List<AlightingAwareFeatureSnapshot> snapshots,
        AlightingAwareRouteStatistics statistics
    ) {
        Map<Long, String> digests = new HashMap<>();
        for (AlightingAwareFeatureSnapshot snapshot : snapshots) {
            digests.put(snapshot.vehicleObservationId(), digest(snapshot, statistics));
        }
        return digests;
    }

    private static String digest(AlightingAwareFeatureSnapshot snapshot, AlightingAwareRouteStatistics statistics) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer header = ByteBuffer.allocate(16);
            header.putLong(snapshot.capacity()).putLong(snapshot.provenance().tripStartObservationId());
            digest.update(header.array());
            for (AlightingAwareRouteStatistics.Stop stop : statistics.stops()) {
                AlightingAwareFeatureVector vector = vector(snapshot, stop.order());
                if (vector == null) {
                    digest.update((byte) 0);
                    continue;
                }
                digest.update((byte) 1);
                ByteBuffer values = ByteBuffer.allocate((88 + 80) * Long.BYTES);
                Arrays.stream(vector.seat88()).forEach(value -> values.putLong(Double.doubleToRawLongBits(value)));
                Arrays.stream(vector.probability80())
                    .forEach(value -> values.putLong(Double.doubleToRawLongBits(value)));
                digest.update(values.array());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Map<Long, String> withheld(ForecastStageResult result) {
        Map<Long, String> reasons = new HashMap<>();
        result.withheld().forEach(vehicle -> reasons.put(vehicle.observationId(), vehicle.reason()));
        return reasons;
    }

    private static final class Tally {
        private int batches;
        private int skipped;
        private int failed;
        private int snapshots;
        private int vectors;
        private int passes;
    }
}
