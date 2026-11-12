package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.InvalidRouteIdException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.domain.Route;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoardQueryServiceBatchReadTest {
    private static final OffsetDateTime OBSERVED_AT = OffsetDateTime.parse("2026-08-27T08:00:00+09:00");
    private static final ForecastModel ACTIVE_MODEL = new ForecastModel(
        9L, "model-active", OffsetDateTime.parse("2026-08-26T23:59:59+09:00"));
    private static final String FIRST = "204000057";
    private static final String SECOND = "204000058";
    private static final String UNPREPARED = "204000059";
    private static final String MISSING = "204000060";
    private static final String DUPLICATED = "204000061";
    private static final String STALE = "204000062";

    private final FakeBoardRepository repository = new FakeBoardRepository();
    private BoardFreshnessPolicy freshness;
    private BoardCachePolicy cachePolicy;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T23:04:59Z"), ZoneId.of("Asia/Seoul"));
        freshness = new BoardFreshnessPolicy(clock);
        cachePolicy = new BoardCachePolicy(clock);
        repository.add(FIRST, 1L, 100L, true, ACTIVE_MODEL);
        repository.add(SECOND, 2L, 200L, false, ACTIVE_MODEL);
    }

    private BoardQueryService service(final boolean cacheEnabled) {
        return new BoardQueryService(repository, freshness, cachePolicy,
            new BoardReadCache(cacheEnabled, new SimpleMeterRegistry()));
    }

    @Test
    void 여러_노선을_종류마다_한_번씩_읽고_노선마다_따로_읽은_결과와_같은_판정보드를_만든다() {
        // given
        BoardQueryService batch = service(false);
        BoardQueryService single = service(false);

        // when
        Map<String, BoardBatchRead> reads = batch.readBoards(List.of(SECOND, FIRST, SECOND), BoardReadDeadline.none());

        // then
        assertThat(reads.keySet()).containsExactly(SECOND, FIRST);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(reads.get(FIRST).reading()).isEqualTo(single.readBoard(new RouteId(FIRST)));
            softly.assertThat(reads.get(SECOND).reading()).isEqualTo(single.readBoard(new RouteId(SECOND)));
            softly.assertThat(repository.batchCalls).containsExactly("snapshots", "stops", "predictions", "vehicles");
            softly.assertThat(repository.requestedPredictionBatches).containsExactly(100L);
        }
    }

    @Test
    void 한_노선의_실패는_그_노선에만_담고_노선마다_따로_읽을_때와_같은_예외를_쓴다() {
        // given
        repository.add(UNPREPARED, 3L, 300L, false, null);
        repository.add(DUPLICATED, 4L, 400L, false, ACTIVE_MODEL);
        repository.add(DUPLICATED, 5L, 500L, false, ACTIVE_MODEL);
        repository.addStale(STALE, 6L, 600L);
        BoardQueryService service = service(true);

        // when
        Map<String, BoardBatchRead> reads = service.readBoards(
            List.of(FIRST, UNPREPARED, MISSING, DUPLICATED, STALE, "bad"), BoardReadDeadline.none());

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(reads.get(FIRST).reading()).isNotNull();
            softly.assertThat(reads.get(UNPREPARED).failure()).isInstanceOf(ModelOutOfScopeException.class);
            softly.assertThat(reads.get(MISSING).failure()).isInstanceOf(RouteNotFoundException.class);
            softly.assertThat(reads.get(DUPLICATED).failure()).isInstanceOf(ServiceUnavailableException.class);
            softly.assertThat(reads.get(STALE).failure()).isInstanceOf(NoRecentObservationException.class);
            softly.assertThat(reads.get("bad").failure()).isInstanceOf(InvalidRouteIdException.class);
        }
        assertThatThrownBy(() -> service.readBoard(new RouteId(UNPREPARED))).isInstanceOf(ModelOutOfScopeException.class);
        assertThatThrownBy(() -> service.readBoard(new RouteId(MISSING))).isInstanceOf(RouteNotFoundException.class);
    }

    @Test
    void 정류장이_없는_노선은_그_노선만_실패한다() {
        // given
        repository.stops.remove(2L);

        // when
        Map<String, BoardBatchRead> reads = service(false).readBoards(List.of(FIRST, SECOND), BoardReadDeadline.none());

        // then
        assertThat(reads.get(FIRST).reading()).isNotNull();
        assertThat(reads.get(SECOND).failure()).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void 캐시에_있는_노선은_스냅샷만_읽고_재사용한다() {
        // given
        BoardQueryService service = service(true);
        BoardReading first = service.readBoards(List.of(FIRST, SECOND), BoardReadDeadline.none()).get(FIRST).reading();
        repository.batchCalls.clear();

        // when
        Map<String, BoardBatchRead> reads = service.readBoards(List.of(FIRST, SECOND), BoardReadDeadline.none());

        // then
        assertThat(reads.get(FIRST).reading().board()).isSameAs(first.board());
        assertThat(repository.batchCalls).containsExactly("snapshots");
    }

    @Test
    void 스냅샷을_읽은_뒤_예산이_지났으면_캐시에_없는_노선만_건너뛴다() {
        // given
        BoardQueryService service = service(true);
        service.readBoards(List.of(FIRST), BoardReadDeadline.none());
        repository.batchCalls.clear();

        // when
        Map<String, BoardBatchRead> reads = service.readBoards(List.of(FIRST, SECOND), BoardReadDeadline.after(Duration.ZERO));

        // then
        assertThat(reads.get(FIRST).reading()).isNotNull();
        assertThat(reads.get(SECOND).skipped()).isTrue();
        assertThat(repository.batchCalls).containsExactly("snapshots");
    }

    @Test
    void 호출_전에_마감이_지났어도_요청_순서의_첫_노선은_조립하고_나머지는_건너뛴다() throws InterruptedException {
        // given
        BoardReadDeadline deadline = BoardReadDeadline.after(Duration.ofMillis(20));
        Thread.sleep(30);

        // when
        Map<String, BoardBatchRead> reads = service(false).readBoards(List.of(SECOND, FIRST), deadline);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(reads.get(SECOND).reading()).isNotNull();
            softly.assertThat(reads.get(FIRST).skipped()).isTrue();
        }
    }

    @Test
    void 마감_전이면_캐시에_없는_노선을_모두_조립한다() {
        // when
        Map<String, BoardBatchRead> reads = service(false).readBoards(List.of(FIRST, SECOND),
            BoardReadDeadline.after(Duration.ofMinutes(1)));

        // then
        assertThat(reads.values()).allSatisfy(read -> assertThat(read.reading()).isNotNull());
    }

    @Test
    void 저장소_조회가_실패하면_노선별로_나누지_않고_던진다() {
        // given
        repository.failStops = true;

        // when, then
        assertThatThrownBy(() -> service(false).readBoards(List.of(FIRST, SECOND), BoardReadDeadline.none()))
            .isInstanceOf(ServiceUnavailableException.class);
    }

    /**
     * 노선 판본마다 회차점이 있는 정류장 3개, 관측 묶음마다 차량 1대를 두는 저장소다. 일괄 조회 호출 순서를 남긴다.
     */
    private static final class FakeBoardRepository implements BoardQueryRepository {
        private final Map<String, List<BoardSnapshot>> snapshots = new HashMap<>();
        private final Map<Long, List<BoardStop>> stops = new HashMap<>();
        private final Map<Long, List<StoredPrediction>> predictions = new HashMap<>();
        private final Map<Long, List<BoardVehicleObservation>> vehicles = new HashMap<>();
        private final List<String> batchCalls = new ArrayList<>();
        private final List<Long> requestedPredictionBatches = new ArrayList<>();
        private boolean failStops;

        void add(String routeId, final long versionId, final long batchId, final boolean published, ForecastModel model) {
            addObserved(routeId, versionId, batchId, published, model, OBSERVED_AT);
        }

        void addStale(String routeId, final long versionId, final long batchId) {
            addObserved(routeId, versionId, batchId, false, ACTIVE_MODEL, OBSERVED_AT.minusHours(3));
        }

        private void addObserved(String routeId, final long versionId, final long batchId, final boolean published,
            ForecastModel model, OffsetDateTime observedAt) {
            snapshots.computeIfAbsent(routeId, ignored -> new ArrayList<>()).add(new BoardSnapshot(
                versionId,
                new Route(routeId, "노선-" + routeId, "기점", "회차점"),
                2,
                new DepartureSchedule("04:50", "23:30", "05:00", "23:30", RouteHeadways.none()),
                Optional.of(new SnapshotObservation(batchId, observedAt, 4, published)),
                Optional.ofNullable(model),
                "revision-" + batchId
            ));
            stops.put(versionId, List.of(
                new BoardStop(1, "STOP-1", "기점", BoardDirection.UP, true),
                new BoardStop(2, "STOP-2", "회차점", BoardDirection.UP, false),
                new BoardStop(3, "STOP-3", "종점", BoardDirection.DOWN, true)
            ));
            vehicles.put(batchId, List.of(new BoardVehicleObservation("vehicle-" + batchId, 1, 1, 1)));
            if (published) {
                predictions.put(batchId, List.of(
                    new StoredPrediction(3, "vehicle-" + batchId, 1, 2, 0.25, 10.0, ACTIVE_MODEL)));
            }
        }

        @Override
        public Optional<BoardSnapshot> findSnapshot(RouteId routeId) {
            List<BoardSnapshot> found = snapshots.getOrDefault(routeId.value(), List.of());
            if (found.size() > 1) {
                throw new ServiceUnavailableException();
            }
            return found.stream().findFirst();
        }

        @Override
        public List<BoardStop> findStops(final long routeVersionId) {
            return stops.getOrDefault(routeVersionId, List.of());
        }

        @Override
        public List<StoredPrediction> findPredictions(final long observationBatchId) {
            return predictions.getOrDefault(observationBatchId, List.of());
        }

        @Override
        public List<BoardVehicleObservation> findObservedVehicles(final long observationBatchId,
            final long routeVersionId) {
            return vehicles.getOrDefault(observationBatchId, List.of());
        }

        @Override
        public List<BoardSnapshot> findSnapshots(Collection<RouteId> routeIds) {
            batchCalls.add("snapshots");
            return routeIds.stream()
                .flatMap(routeId -> snapshots.getOrDefault(routeId.value(), List.of()).stream())
                .toList();
        }

        @Override
        public Map<Long, List<BoardStop>> findStops(Collection<Long> routeVersionIds) {
            batchCalls.add("stops");
            if (failStops) {
                throw new ServiceUnavailableException();
            }
            return BoardQueryRepository.super.findStops(routeVersionIds);
        }

        @Override
        public Map<Long, List<StoredPrediction>> findPredictions(Collection<Long> observationBatchIds) {
            batchCalls.add("predictions");
            requestedPredictionBatches.addAll(observationBatchIds);
            return BoardQueryRepository.super.findPredictions(observationBatchIds);
        }

        @Override
        public Map<Long, List<BoardVehicleObservation>> findObservedVehicles(Map<Long, Long> routeVersionIdByBatchId) {
            batchCalls.add("vehicles");
            return BoardQueryRepository.super.findObservedVehicles(routeVersionIdByBatchId);
        }
    }
}
