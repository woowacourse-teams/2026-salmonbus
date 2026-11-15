package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.board.infrastructure.jpa.JpaBoardQueryRepository;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.RouteStatus;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예보 기록에서 읽은 탑승판·스냅샷이 기록해 둔 기대 응답과 필드 하나까지 같은지 확인한다.
 *
 * <p>기대 응답은 시험 자원 {@code board/forecast-source-equivalence.txt}에 있다. 같은 예측을 이전 저장 테이블에서 읽던 조회와 예보 기록에서 읽는
 * 조회가 같은 응답을 내던 때 기록했고, 비교 방법은 {@link RecordedBoards}가 정한다.
 */
@IntegrationTest
@Transactional
class ForecastSourceEquivalenceTest {
    private static final Map<String, String> RECORDED = RecordedBoards.load("/board/forecast-source-equivalence.txt");
    private static final String ROUTE_ID = "204000057";
    private static final String OTHER_ROUTE_ID = "234000050";
    private static final int STOP_COUNT = 14;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private JpaBoardQueryRepository repository;

    @Autowired
    private BoardFreshnessPolicy freshnessPolicy;

    @Autowired
    private BoardCachePolicy cachePolicy;

    private BoardDatabaseFixture fixture;
    private OffsetDateTime now;
    private long model;
    private RecordedBoards format;

    @BeforeEach
    void setUp() {
        fixture = new BoardDatabaseFixture(jdbc);
        now = OffsetDateTime.now(clock).withNano(0);
        model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        format = new RecordedBoards(now).model(model, "active");
    }

    @Test
    void 예측이_있는_차량과_없는_차량_목표_12개와_비어_있는_기대_좌석을_기록한_응답과_같게_읽는다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        Batch batch = name(batchWithVehicles(route, now), "batch");
        publish(batch, now, model);

        // when
        Board board = boardOf(ROUTE_ID);

        // then
        assertSoftly(softly -> {
            softly.assertThat(format.board(board)).isEqualTo(RECORDED.get("vehicles.board"));
            softly.assertThat(board.route().status()).isEqualTo(RouteStatus.FORECAST_READY);
            ApproachingVehicle first = vehicleAt(board, 2, "BUS-A");
            softly.assertThat(first.forecast()).isEqualTo(new VehicleForecast.Available(
                0.9, 18.0, "AFTER_ALIGHTING_ESTIMATE", "DEPARTURE_ZERO", 0.1));
            softly.assertThat(((VehicleForecast.Available) vehicleAt(board, 5, "BUS-A").forecast()).expectedSeats())
                .isNull();
            softly.assertThat(((VehicleForecast.Available) vehicleAt(board, 6, "BUS-A").forecast()).expectedSeats())
                .isNull();
            softly.assertThat(((VehicleForecast.Available) vehicleAt(board, 7, "BUS-A").forecast()).expectedSeats())
                .isNull();
            softly.assertThat(vehicleAt(board, 13, "BUS-A").forecast()).isInstanceOf(VehicleForecast.Available.class);
            softly.assertThat(vehicleAt(board, 2, null).forecast()).isInstanceOf(VehicleForecast.Unavailable.class);
            softly.assertThat(vehicleAt(board, 5, "BUS-B").forecast()).isInstanceOf(VehicleForecast.Unavailable.class);
        });
    }

    @Test
    void 판본_표시는_기록한_판본_표시와_같고_출처_표시가_없다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        Batch batch = name(batchWithVehicles(route, now), "batch");
        publish(batch, now, model);

        // when
        BoardSnapshot snapshot = snapshotOf(ROUTE_ID);

        // then
        assertSoftly(softly -> {
            softly.assertThat(format.snapshot(snapshot)).isEqualTo(RECORDED.get("revision.snapshot"));
            softly.assertThat(snapshot.revision()).matches("p[^|]+\\|g[^|]+\\|n14\\|m" + model + "\\|d0\\|r1");
            softly.assertThat(snapshot.observation().orElseThrow().batchId()).isEqualTo(batch.id());
        });
    }

    @Test
    void 예측이_0건인_실행도_빈_발행처럼_최신_배치가_되고_예보는_없다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        Batch older = name(batchWithVehicles(route, now.minusSeconds(30)), "older");
        publish(older, now.minusSeconds(29), model);
        Batch newer = name(batchWithVehicles(route, now), "newer");
        fixture.insertRun(newer.id(), model, "EMPTY", now);

        // when
        Board board = boardOf(ROUTE_ID);

        // then
        assertSoftly(softly -> {
            softly.assertThat(format.board(board)).isEqualTo(RECORDED.get("emptyRun.board"));
            softly.assertThat(board.observedAt()).isEqualTo(now);
            softly.assertThat(board.route().status()).isEqualTo(RouteStatus.FORECAST_READY);
            softly.assertThat(vehicleAt(board, 2, "BUS-A").forecast()).isInstanceOf(VehicleForecast.Unavailable.class);
            softly.assertThat(format.snapshot(snapshotOf(ROUTE_ID))).isEqualTo(RECORDED.get("emptyRun.snapshot"));
        });
    }

    @Test
    void 예보하지_않은_실행_결과도_최신_배치로_잡혀_예보_없음이_된다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        Batch older = name(batchWithVehicles(route, now.minusSeconds(30)), "older");
        publish(older, now.minusSeconds(29), model);
        Batch newer = name(batchWithVehicles(route, now), "newer");
        fixture.insertRun(newer.id(), null, "LATE", now);

        // when
        Board board = boardOf(ROUTE_ID);

        // then
        assertThat(format.board(board)).isEqualTo(RECORDED.get("lateRun.board"));
        assertThat(vehicleAt(board, 2, "BUS-A").forecast()).isInstanceOf(VehicleForecast.Unavailable.class);
    }

    @Test
    void 발행된_배치가_신선도_창_밖이면_최근_관측이_없다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        OffsetDateTime old = now.minusMinutes(10).minusSeconds(5);
        Batch stale = name(batchWithVehicles(route, old), "stale");
        publish(stale, old, model);
        format.batch(fixture.insertBatch(route, now, "SUCCESS_EMPTY", 0), "latest");

        // when
        Throwable actual = catchThrowable(() -> boardOf(ROUTE_ID));

        // then
        assertThat(actual).isInstanceOf(NoRecentObservationException.class);
        assertThat(RecordedBoards.failure(actual)).isEqualTo(RECORDED.get("stale.failure"));
        assertThat(snapshotOf(ROUTE_ID).observation()).isEmpty();
        assertThat(format.snapshot(snapshotOf(ROUTE_ID))).isEqualTo(RECORDED.get("stale.snapshot"));
    }

    @Test
    void 노선에_발행이_한_번도_없으면_최신_정상_관측으로_읽는다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        name(batchWithVehicles(route, now.minusSeconds(20)), "earlier");
        Batch latest = name(batchWithVehicles(route, now), "latest");

        // when
        Board board = boardOf(ROUTE_ID);

        // then
        assertSoftly(softly -> {
            softly.assertThat(format.board(board)).isEqualTo(RECORDED.get("neverPublished.board"));
            softly.assertThat(board.route().status()).isEqualTo(RouteStatus.PREPARING);
            BoardSnapshot snapshot = snapshotOf(ROUTE_ID);
            softly.assertThat(snapshot.observation().orElseThrow().batchId()).isEqualTo(latest.id());
            softly.assertThat(snapshot.observation().orElseThrow().forecastPublished()).isFalse();
            softly.assertThat(snapshot.revision()).isEqualTo("p-|g-|n-|m-|d-|r-");
            softly.assertThat(format.snapshot(snapshot)).isEqualTo(RECORDED.get("neverPublished.snapshot"));
        });
    }

    @Test
    void 예측의_모델은_실행_줄의_모델_하나로_읽는다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        Batch batch = name(batchWithVehicles(route, now), "batch");
        publish(batch, now, model);

        // when
        Board board = boardOf(ROUTE_ID);

        // then
        assertThat(format.board(board)).isEqualTo(RECORDED.get("mixedModels.board"));
        assertThat(board.model().releaseId()).isEqualTo("model-active");
    }

    @Test
    void 모든_예측이_활성이_아닌_모델이면_그_모델을_응답한다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        Batch batch = name(batchWithVehicles(route, now), "batch");
        final long retired = fixture.insertModel("model-retired", "RETIRED", now.minusDays(2));
        format.model(retired, "retired");
        publish(batch, now, retired);

        // when
        Board board = boardOf(ROUTE_ID);

        // then
        assertThat(format.board(board)).isEqualTo(RECORDED.get("retiredModel.board"));
        assertThat(board.model().releaseId()).isEqualTo("model-retired");
    }

    @Test
    void 실행_줄의_모델_배포를_찾을_수_없으면_서비스_불가다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        Batch batch = batchWithVehicles(route, now);
        publish(batch, now, Long.MAX_VALUE);

        // when
        Throwable actual = catchThrowable(() -> boardOf(ROUTE_ID));

        // then
        assertThat(actual).isInstanceOf(ServiceUnavailableException.class);
        assertThat(RecordedBoards.failure(actual)).isEqualTo(RECORDED.get("missingModel.failure"));
    }

    @Test
    void 일괄_읽기도_노선마다_기록한_판정보드를_낸다() {
        // given
        BoardDatabaseFixture.RouteContext published = route(ROUTE_ID);
        BoardDatabaseFixture.RouteContext observed = route(OTHER_ROUTE_ID);
        publish(name(batchWithVehicles(published, now), "published"), now, model);
        name(batchWithVehicles(observed, now), "observed");
        List<String> routeIds = List.of(ROUTE_ID, "999999999", OTHER_ROUTE_ID);

        // when
        Map<String, BoardBatchRead> reads = service().readBoards(routeIds, BoardReadDeadline.none());

        // then
        assertThat(reads.keySet()).containsExactlyElementsOf(routeIds);
        assertThat(format.batchReads(reads)).isEqualTo(RECORDED.get("batchRead.reads"));
        for (String routeId : List.of(ROUTE_ID, OTHER_ROUTE_ID)) {
            BoardReading reading = reads.get(routeId).reading();
            assertThat(reading.board()).isEqualTo(boardOf(routeId));
            assertThat(reading.cacheMaxAge()).isEqualTo(cachePolicy.maxAgeAt(reading.board().observedAt()));
        }
    }

    @Test
    void 새_배치가_오면_판본_표시가_바뀌어_캐시한_판정보드를_다시_만든다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        publish(batchWithVehicles(route, now.minusSeconds(20)), now.minusSeconds(20), model);
        BoardQueryService service = new BoardQueryService(repository, freshnessPolicy, cachePolicy,
            new BoardReadCache(true, null));
        Board first = service.readBoard(new RouteId(ROUTE_ID)).board();
        String firstRevision = snapshotOf(ROUTE_ID).revision();
        assertThat(service.readBoard(new RouteId(ROUTE_ID)).board()).isSameAs(first);

        // when
        publish(batchWithVehicles(route, now), now, model);
        Board second = service.readBoard(new RouteId(ROUTE_ID)).board();

        // then
        assertThat(second.observedAt()).isEqualTo(now);
        assertThat(second).isNotEqualTo(first);
        assertThat(snapshotOf(ROUTE_ID).revision()).isNotEqualTo(firstRevision);
    }

    @Test
    void 실행_줄이_없는_더_최신_관측보다_실행_줄이_있는_배치를_읽는다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        Batch older = name(batchWithVehicles(route, now.minusSeconds(20)), "older");
        name(batchWithVehicles(route, now), "newer");
        publish(older, now.minusSeconds(20), model);

        // when
        BoardSnapshot snapshot = snapshotOf(ROUTE_ID);

        // then
        assertThat(format.snapshot(snapshot)).isEqualTo(RECORDED.get("recordOlder.snapshot"));
        assertThat(snapshot.observation().orElseThrow().batchId()).isEqualTo(older.id());
        assertThat(format.board(boardOf(ROUTE_ID))).isEqualTo(RECORDED.get("recordOlder.board"));
    }

    @Test
    void 실행_줄이_있는_최신_배치를_읽는다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        name(batchWithVehicles(route, now.minusSeconds(20)), "older");
        Batch newer = name(batchWithVehicles(route, now), "newer");
        publish(newer, now, model);

        // when
        BoardSnapshot snapshot = snapshotOf(ROUTE_ID);

        // then
        assertSoftly(softly -> {
            softly.assertThat(format.snapshot(snapshot)).isEqualTo(RECORDED.get("recordNewer.snapshot"));
            softly.assertThat(snapshot.observation().orElseThrow().batchId()).isEqualTo(newer.id());
            softly.assertThat(format.board(boardOf(ROUTE_ID))).isEqualTo(RECORDED.get("recordNewer.board"));
        });
    }

    @Test
    void 실행_줄이_하나인_배치를_읽는다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        publish(name(batchWithVehicles(route, now), "batch"), now, model);

        // when
        BoardSnapshot snapshot = snapshotOf(ROUTE_ID);

        // then
        assertThat(format.snapshot(snapshot)).isEqualTo(RECORDED.get("sameBatch.snapshot"));
        assertThat(format.board(boardOf(ROUTE_ID))).isEqualTo(RECORDED.get("sameBatch.board"));
    }

    @Test
    void 노선마다_실행_줄이_있는_배치_또는_최신_정상_관측을_읽는다() {
        // given
        BoardDatabaseFixture.RouteContext recorded = route(ROUTE_ID);
        BoardDatabaseFixture.RouteContext observedOnly = route(OTHER_ROUTE_ID);
        publish(name(batchWithVehicles(recorded, now.minusSeconds(20)), "recorded"), now.minusSeconds(20), model);
        name(batchWithVehicles(recorded, now), "recordedLatest");
        name(batchWithVehicles(observedOnly, now.minusSeconds(20)), "observedOnlyEarlier");
        name(batchWithVehicles(observedOnly, now), "observedOnlyLatest");

        // when
        BoardSnapshot recordedSnapshot = snapshotOf(ROUTE_ID);
        BoardSnapshot observedOnlySnapshot = snapshotOf(OTHER_ROUTE_ID);

        // then
        assertSoftly(softly -> {
            softly.assertThat(format.snapshot(recordedSnapshot)).isEqualTo(RECORDED.get("oneSource.recorded.snapshot"));
            softly.assertThat(recordedSnapshot.observation().orElseThrow().observedAt())
                .isEqualTo(now.minusSeconds(20));
            softly.assertThat(format.snapshot(observedOnlySnapshot))
                .isEqualTo(RECORDED.get("oneSource.observedOnly.snapshot"));
            softly.assertThat(observedOnlySnapshot.observation().orElseThrow().observedAt()).isEqualTo(now);
            List<BoardSnapshot> many = repository.findSnapshots(
                Stream.of(ROUTE_ID, OTHER_ROUTE_ID).map(RouteId::new).toList());
            softly.assertThat(many).containsExactlyInAnyOrder(recordedSnapshot, observedOnlySnapshot);
            softly.assertThat(String.join(" || ", many.stream().map(format::snapshot).sorted().toList()))
                .isEqualTo(RECORDED.get("oneSource.many"));
        });
    }

    @Test
    void 신선도_창_밖의_관측보다_창_안의_실행_줄_배치를_읽는다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        OffsetDateTime old = now.minusMinutes(10).minusSeconds(5);
        name(batchWithVehicles(route, old), "old");
        Batch fresh = name(batchWithVehicles(route, now), "fresh");
        publish(fresh, now, model);

        // when
        BoardSnapshot snapshot = snapshotOf(ROUTE_ID);

        // then
        assertThat(snapshot.observation().orElseThrow().batchId()).isEqualTo(fresh.id());
        assertThat(format.snapshot(snapshot)).isEqualTo(RECORDED.get("staleObservation.snapshot"));
    }

    @Test
    void 실행_줄이_생길_때마다_그_배치를_읽고_예보하지_않은_실행도_최신_배치가_된다() {
        // given
        BoardDatabaseFixture.RouteContext route = route(ROUTE_ID);
        publish(name(batchWithVehicles(route, now.minusSeconds(60)), "first"), now.minusSeconds(60), model);
        Batch second = name(batchWithVehicles(route, now.minusSeconds(30)), "second");
        publish(second, now.minusSeconds(30), model);

        // when
        BoardSnapshot beforeThird = snapshotOf(ROUTE_ID);
        Board boardBeforeThird = boardOf(ROUTE_ID);
        Batch third = name(batchWithVehicles(route, now), "third");
        publish(third, now, model);
        BoardSnapshot afterThird = snapshotOf(ROUTE_ID);
        Board boardAfterThird = boardOf(ROUTE_ID);
        Batch late = name(batchWithVehicles(route, now.plusSeconds(30)), "late");
        fixture.insertRun(late.id(), model, "LATE", now.plusSeconds(121));
        BoardSnapshot afterLateRun = snapshotOf(ROUTE_ID);

        // then
        assertSoftly(softly -> {
            softly.assertThat(beforeThird.observation().orElseThrow().batchId()).isEqualTo(second.id());
            softly.assertThat(format.snapshot(beforeThird)).isEqualTo(RECORDED.get("sequence.beforeThird.snapshot"));
            softly.assertThat(format.board(boardBeforeThird)).isEqualTo(RECORDED.get("sequence.beforeThird.board"));
            softly.assertThat(vehicleAt(boardBeforeThird, 2, "BUS-A").forecast())
                .isInstanceOf(VehicleForecast.Available.class);
            softly.assertThat(afterThird.observation().orElseThrow().batchId()).isEqualTo(third.id());
            softly.assertThat(format.snapshot(afterThird)).isEqualTo(RECORDED.get("sequence.afterThird.snapshot"));
            softly.assertThat(format.board(boardAfterThird)).isEqualTo(RECORDED.get("sequence.afterThird.board"));
            softly.assertThat(vehicleAt(boardAfterThird, 2, "BUS-A").forecast())
                .isInstanceOf(VehicleForecast.Available.class);
            softly.assertThat(afterLateRun.observation().orElseThrow().batchId()).isEqualTo(late.id());
            softly.assertThat(format.snapshot(afterLateRun)).isEqualTo(RECORDED.get("sequence.afterLate.snapshot"));
        });
    }

    private Board boardOf(String routeId) {
        return service().readBoard(new RouteId(routeId)).board();
    }

    private BoardQueryService service() {
        return new BoardQueryService(repository, freshnessPolicy, cachePolicy, new BoardReadCache(false, null));
    }

    private BoardSnapshot snapshotOf(String routeId) {
        return repository.findSnapshot(new RouteId(routeId)).orElseThrow();
    }

    private static ApproachingVehicle vehicleAt(Board board, final int stopSequence, String vehicleId) {
        StopState stop = board.stops().stream()
            .filter(candidate -> candidate.sequence() == stopSequence)
            .findFirst()
            .orElseThrow();
        return stop.approachingVehicles().stream()
            .filter(vehicle -> Objects.equals(vehicle.vehicleId(), vehicleId))
            .findFirst()
            .orElseThrow();
    }

    private BoardDatabaseFixture.RouteContext route(String routeId) {
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(routeId, "노선-" + routeId, "기점", "종점", null,
            "05:00", "23:00", null, null, now.minusDays(1));
        for (int sequence = 1; sequence <= STOP_COUNT; sequence++) {
            fixture.insertStop(route, sequence, "STOP-" + sequence, "정류장-" + sequence, "UP", true);
        }
        format.version(route.routeVersionId(), routeName(routeId));
        return route;
    }

    private Batch name(Batch batch, String name) {
        format.batch(batch.id(), name);
        return batch;
    }

    private static String routeName(String routeId) {
        if (routeId.equals(ROUTE_ID)) {
            return "route";
        }
        return "other";
    }

    /**
     * 차량 세 대가 있는 배치다. A는 1번에서 목표 12개, B는 2번에서 목표 2개의 예측을 받고, 차량 번호가 없는 C는 예측이 없다.
     */
    private Batch batchWithVehicles(BoardDatabaseFixture.RouteContext route, OffsetDateTime receivedAt) {
        final long batch = fixture.insertBatch(route, receivedAt, "SUCCESS_ROWS", 3);
        final long busA = fixture.insertObservation(route, batch, 1, "BUS-A", 1, "STOP-1", receivedAt);
        final long busB = fixture.insertObservation(route, batch, 2, "BUS-B", 2, "STOP-2", receivedAt);
        fixture.insertObservation(route, batch, 3, null, 1, "STOP-1", receivedAt);
        return new Batch(batch, busA, busB);
    }

    /** 목표 5는 비어 있는 원소, 6은 NaN, 7은 무한대다. */
    private void publish(Batch batch, OffsetDateTime at, final long modelId) {
        insertRun(batch.id(), at, modelId, "PUBLISHED", 14);
        StringBuilder chances = new StringBuilder();
        StringBuilder seats = new StringBuilder();
        StringBuilder targets = new StringBuilder();
        for (int target = 2; target <= 13; target++) {
            String separator = separatorBefore(target);
            targets.append(separator).append(target);
            chances.append(separator).append(chanceOf(target));
            seats.append(separator).append(seatsOf(target));
        }
        insertRecord(batch.busA(), "{" + targets + "}", "{" + seats + "}", "{" + chances + "}");
        insertRecord(batch.busB(), "{3,4}", "{2.5,1.0}", "{0.4,0.6}");
    }

    private static String separatorBefore(final int target) {
        if (target == 2) {
            return "";
        }
        return ",";
    }

    private static double chanceOf(final int target) {
        return target / 20.0;
    }

    private static String seatsOf(final int target) {
        return switch (target) {
            case 5 -> "NULL";
            case 6 -> "NaN";
            case 7 -> "Infinity";
            default -> String.valueOf(20.0 - target);
        };
    }

    private void insertRun(
        final long batchId,
        OffsetDateTime confirmedAt,
        Long modelId,
        String outcome,
        final int predictionCount
    ) {
        jdbc.sql("""
            INSERT INTO forecast_run (source_date, observation_batch_id, route_id, route_version_id, received_at,
                confirmed_at, model_deployment_id, outcome, prediction_count)
            SELECT (batch.scheduled_at AT TIME ZONE 'Asia/Seoul')::date, batch.id, version.route_id,
                batch.route_version_id, batch.response_received_at, :confirmedAt, :modelId, :outcome, :predictionCount
            FROM observation_batch batch
            JOIN route_version version ON version.id = batch.route_version_id
            WHERE batch.id = :batchId
            """)
            .param("confirmedAt", confirmedAt)
            .param("modelId", modelId)
            .param("outcome", outcome)
            .param("predictionCount", predictionCount)
            .param("batchId", batchId)
            .update();
    }

    private void insertRecord(final long observationId, String targets, String seats, String chances) {
        jdbc.sql("""
            INSERT INTO forecast_record (source_date, vehicle_observation_id, observation_batch_id, source_row_number,
                route_id, route_version_id, vehicle_id, passed_stop_order, trip_start_observation_id,
                chain_start_observation_id, down, capacity, extended, target_stop_orders, expected_seats,
                full_probabilities)
            SELECT run.source_date, observation.id, observation.observation_batch_id, observation.source_row_number,
                run.route_id, observation.route_version_id, observation.vehicle_id, observation.passed_stop_order,
                observation.id, observation.id, false, 45, false, CAST(:targets AS integer[]),
                CAST(:seats AS double precision[]), CAST(:chances AS double precision[])
            FROM vehicle_observation observation
            JOIN forecast_run run ON run.observation_batch_id = observation.observation_batch_id
            WHERE observation.id = :observationId
            """)
            .param("targets", targets)
            .param("seats", seats)
            .param("chances", chances)
            .param("observationId", observationId)
            .update();
    }

    private record Batch(long id, long busA, long busB) {
    }
}
