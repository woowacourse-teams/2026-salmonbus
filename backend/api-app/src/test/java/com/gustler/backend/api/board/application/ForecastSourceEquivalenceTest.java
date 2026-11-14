package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.api.board.ForecastSourceSetting;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.board.infrastructure.ForecastSourceBoardQueryRepository;
import com.gustler.backend.api.board.infrastructure.jdbc.JdbcBoardSnapshotReader;
import com.gustler.backend.api.board.infrastructure.jdbc.JdbcForecastRecordReader;
import com.gustler.backend.api.board.infrastructure.jpa.JpaBoardQueryRepository;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.RouteStatus;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * 같은 예측을 옛 예보 테이블과 새 예보 기록에 넣었을 때 두 출처의 탑승판이 필드 하나까지 같은지 확인한다.
 */
@IntegrationTest
@Transactional
class ForecastSourceEquivalenceTest {
    private static final String ROUTE_ID = "204000057";
    private static final String OTHER_ROUTE_ID = "234000050";
    private static final int STOP_COUNT = 14;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private JpaBoardQueryRepository jpaBoardQueryRepository;

    @Autowired
    private JdbcBoardSnapshotReader snapshotReader;

    @Autowired
    private JdbcForecastRecordReader recordReader;

    @Autowired
    private BoardFreshnessPolicy freshnessPolicy;

    @Autowired
    private BoardCachePolicy cachePolicy;

    private BoardDatabaseFixture fixture;
    private OffsetDateTime now;
    private long model;

    @BeforeEach
    void setUp() {
        fixture = new BoardDatabaseFixture(jdbc);
        now = OffsetDateTime.now(clock).withNano(0);
        model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
    }

    @Test
    void 예측이_있는_차량과_없는_차량_목표_12개와_비어_있는_기대_좌석을_두_출처가_같게_읽는다() {
        // given
        RouteContext route = route(ROUTE_ID);
        Batch batch = batchWithVehicles(route, now);
        publishBoth(batch, now);

        // when
        Board legacy = boardOf(ForecastSourceSetting.LEGACY, ROUTE_ID);
        Board record = boardOf(ForecastSourceSetting.RECORD, ROUTE_ID);

        // then
        assertThat(record).isEqualTo(legacy);
        assertThat(legacy.route().status()).isEqualTo(RouteStatus.FORECAST_READY);
        ApproachingVehicle first = vehicleAt(legacy, 2, "BUS-A");
        assertThat(first.forecast()).isEqualTo(new VehicleForecast.Available(
            0.9, 18.0, "AFTER_ALIGHTING_ESTIMATE", "DEPARTURE_ZERO", 0.1));
        assertThat(((VehicleForecast.Available) vehicleAt(legacy, 5, "BUS-A").forecast()).expectedSeats()).isNull();
        assertThat(((VehicleForecast.Available) vehicleAt(legacy, 6, "BUS-A").forecast()).expectedSeats()).isNull();
        assertThat(((VehicleForecast.Available) vehicleAt(legacy, 7, "BUS-A").forecast()).expectedSeats()).isNull();
        assertThat(vehicleAt(legacy, 13, "BUS-A").forecast()).isInstanceOf(VehicleForecast.Available.class);
        assertThat(vehicleAt(legacy, 2, null).forecast()).isInstanceOf(VehicleForecast.Unavailable.class);
        assertThat(vehicleAt(legacy, 5, "BUS-B").forecast()).isInstanceOf(VehicleForecast.Unavailable.class);
    }

    @Test
    void 판본_표시는_출처_표시만_다르고_옛_출처는_지금과_같은_모양이다() {
        // given
        RouteContext route = route(ROUTE_ID);
        Batch batch = batchWithVehicles(route, now);
        publishBoth(batch, now);

        // when
        BoardSnapshot legacy = snapshotOf(ForecastSourceSetting.LEGACY, ROUTE_ID);
        BoardSnapshot record = snapshotOf(ForecastSourceSetting.RECORD, ROUTE_ID);

        // then
        assertThat(legacy.revision()).matches("p[^|]+\\|g[^|]+\\|n14\\|m" + model + "\\|d0\\|r1");
        assertThat(record.revision()).isEqualTo(legacy.revision() + "|R");
        assertThat(record.observation().orElseThrow().forecastSource()).isEqualTo(ForecastSource.RECORD);
        assertThat(record.observation().orElseThrow().batchId()).isEqualTo(batch.id());
        assertThat(record).isNotEqualTo(legacy);
    }

    @Test
    void 예측이_0건인_실행도_빈_발행처럼_최신_배치가_되고_예보는_없다() {
        // given
        RouteContext route = route(ROUTE_ID);
        Batch older = batchWithVehicles(route, now.minusSeconds(30));
        publishBoth(older, now.minusSeconds(29));
        Batch newer = batchWithVehicles(route, now);
        fixture.insertPublication(newer.id(), model, now, now);
        productionRevisions(newer.id());
        insertRun(newer.id(), now, model, "EMPTY", 0);

        // when
        Board legacy = boardOf(ForecastSourceSetting.LEGACY, ROUTE_ID);
        Board record = boardOf(ForecastSourceSetting.RECORD, ROUTE_ID);

        // then
        assertThat(record).isEqualTo(legacy);
        assertThat(legacy.observedAt()).isEqualTo(now);
        assertThat(legacy.route().status()).isEqualTo(RouteStatus.FORECAST_READY);
        assertThat(vehicleAt(legacy, 2, "BUS-A").forecast()).isInstanceOf(VehicleForecast.Unavailable.class);
        assertThat(snapshotOf(ForecastSourceSetting.RECORD, ROUTE_ID).revision())
            .isEqualTo(snapshotOf(ForecastSourceSetting.LEGACY, ROUTE_ID).revision() + "|R");
    }

    @Test
    void 예보하지_않은_실행_결과도_최신_배치로_잡혀_예보_없음이_된다() {
        // given
        RouteContext route = route(ROUTE_ID);
        Batch older = batchWithVehicles(route, now.minusSeconds(30));
        publishBoth(older, now.minusSeconds(29));
        Batch newer = batchWithVehicles(route, now);
        fixture.insertPublication(newer.id(), model, now, now);
        insertRun(newer.id(), now, null, "LATE", 0);

        // when
        Board legacy = boardOf(ForecastSourceSetting.LEGACY, ROUTE_ID);
        Board record = boardOf(ForecastSourceSetting.RECORD, ROUTE_ID);

        // then
        assertThat(record).isEqualTo(legacy);
        assertThat(vehicleAt(record, 2, "BUS-A").forecast()).isInstanceOf(VehicleForecast.Unavailable.class);
    }

    @Test
    void 발행된_배치가_신선도_창_밖이면_두_출처_모두_최근_관측이_없다() {
        // given
        RouteContext route = route(ROUTE_ID);
        OffsetDateTime old = now.minusMinutes(10).minusSeconds(5);
        Batch stale = batchWithVehicles(route, old);
        publishBoth(stale, old);
        fixture.insertBatch(route, now, "SUCCESS_EMPTY", 0);

        // when
        Throwable legacy = catchThrowable(() -> boardOf(ForecastSourceSetting.LEGACY, ROUTE_ID));
        Throwable record = catchThrowable(() -> boardOf(ForecastSourceSetting.RECORD, ROUTE_ID));

        // then
        assertThat(legacy).isInstanceOf(NoRecentObservationException.class);
        assertThat(record).isInstanceOf(NoRecentObservationException.class);
        assertThat(snapshotOf(ForecastSourceSetting.RECORD, ROUTE_ID).observation()).isEmpty();
    }

    @Test
    void 노선에_발행이_한_번도_없으면_두_출처_모두_최신_정상_관측으로_읽는다() {
        // given
        RouteContext route = route(ROUTE_ID);
        batchWithVehicles(route, now.minusSeconds(20));
        Batch latest = batchWithVehicles(route, now);

        // when
        Board legacy = boardOf(ForecastSourceSetting.LEGACY, ROUTE_ID);
        Board record = boardOf(ForecastSourceSetting.RECORD, ROUTE_ID);

        // then
        assertThat(record).isEqualTo(legacy);
        assertThat(legacy.route().status()).isEqualTo(RouteStatus.PREPARING);
        BoardSnapshot recordSnapshot = snapshotOf(ForecastSourceSetting.RECORD, ROUTE_ID);
        assertThat(recordSnapshot.observation().orElseThrow().batchId()).isEqualTo(latest.id());
        assertThat(recordSnapshot.observation().orElseThrow().forecastPublished()).isFalse();
        assertThat(snapshotOf(ForecastSourceSetting.LEGACY, ROUTE_ID).revision()).isEqualTo("p-|g-|n-|m-|d-|r-");
        assertThat(recordSnapshot.revision()).isEqualTo("p-|g-|n-|m-|d-|r-|R");
    }

    @Test
    void 옛_출처는_예측의_모델이_섞이면_서비스_불가이고_새_출처는_실행_줄의_모델_하나로_읽는다() {
        // given
        RouteContext route = route(ROUTE_ID);
        Batch batch = batchWithVehicles(route, now);
        publishBoth(batch, now);
        final long retired = fixture.insertModel("model-retired", "RETIRED", now.minusDays(2));
        jdbc.sql("""
            UPDATE seat_forecast SET model_deployment_id=? WHERE vehicle_observation_id=? AND target_stop_order=2
            """).params(retired, batch.busA()).update();

        // when
        Throwable legacy = catchThrowable(() -> boardOf(ForecastSourceSetting.LEGACY, ROUTE_ID));
        Board record = boardOf(ForecastSourceSetting.RECORD, ROUTE_ID);

        // then
        assertThat(legacy).isInstanceOf(ServiceUnavailableException.class);
        assertThat(record.model().releaseId()).isEqualTo("model-active");
    }

    @Test
    void 모든_예측이_활성이_아닌_모델이면_두_출처_모두_그_모델을_응답한다() {
        // given
        RouteContext route = route(ROUTE_ID);
        Batch batch = batchWithVehicles(route, now);
        final long retired = fixture.insertModel("model-retired", "RETIRED", now.minusDays(2));
        publishLegacy(batch, now, retired);
        publishRecord(batch, now, retired);

        // when
        Board legacy = boardOf(ForecastSourceSetting.LEGACY, ROUTE_ID);
        Board record = boardOf(ForecastSourceSetting.RECORD, ROUTE_ID);

        // then
        assertThat(record).isEqualTo(legacy);
        assertThat(record.model().releaseId()).isEqualTo("model-retired");
    }

    @Test
    void 새_출처에서_실행_줄의_모델_배포를_찾을_수_없으면_서비스_불가다() {
        // given
        RouteContext route = route(ROUTE_ID);
        Batch batch = batchWithVehicles(route, now);
        publishRecord(batch, now, Long.MAX_VALUE);

        // when
        Throwable actual = catchThrowable(() -> boardOf(ForecastSourceSetting.RECORD, ROUTE_ID));

        // then
        assertThat(actual).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void 일괄_읽기도_두_출처가_노선마다_같은_판정보드를_낸다() {
        // given
        RouteContext published = route(ROUTE_ID);
        RouteContext observed = route(OTHER_ROUTE_ID);
        publishBoth(batchWithVehicles(published, now), now);
        batchWithVehicles(observed, now);
        List<String> routeIds = List.of(ROUTE_ID, "999999999", OTHER_ROUTE_ID);

        // when
        Map<String, BoardBatchRead> legacy = serviceOf(ForecastSourceSetting.LEGACY)
            .readBoards(routeIds, BoardReadDeadline.none());
        Map<String, BoardBatchRead> record = serviceOf(ForecastSourceSetting.RECORD)
            .readBoards(routeIds, BoardReadDeadline.none());

        // then
        assertThat(record.keySet()).containsExactlyElementsOf(legacy.keySet());
        for (String routeId : List.of(ROUTE_ID, OTHER_ROUTE_ID)) {
            assertThat(record.get(routeId).reading()).isEqualTo(legacy.get(routeId).reading());
            assertThat(record.get(routeId).reading().board()).isEqualTo(boardOf(ForecastSourceSetting.RECORD, routeId));
        }
        assertThat(record.get("999999999").failure()).isInstanceOf(legacy.get("999999999").failure().getClass());
    }

    @Test
    void 새_출처에서_새_배치가_오면_판본_표시가_바뀌어_캐시한_판정보드를_다시_만든다() {
        // given
        RouteContext route = route(ROUTE_ID);
        publishRecord(batchWithVehicles(route, now.minusSeconds(20)), now.minusSeconds(20), model);
        BoardQueryService service = new BoardQueryService(sourceOf(ForecastSourceSetting.RECORD), freshnessPolicy,
            cachePolicy, new BoardReadCache(true, null));
        Board first = service.readBoard(new RouteId(ROUTE_ID)).board();
        String firstRevision = snapshotOf(ForecastSourceSetting.RECORD, ROUTE_ID).revision();
        assertThat(service.readBoard(new RouteId(ROUTE_ID)).board()).isSameAs(first);

        // when
        publishRecord(batchWithVehicles(route, now), now, model);
        Board second = service.readBoard(new RouteId(ROUTE_ID)).board();

        // then
        assertThat(second.observedAt()).isEqualTo(now);
        assertThat(second).isNotEqualTo(first);
        assertThat(snapshotOf(ForecastSourceSetting.RECORD, ROUTE_ID).revision()).isNotEqualTo(firstRevision);
    }

    @Test
    void 자동_선택은_옛_출처의_배치가_더_최신이면_옛_출처를_쓴다() {
        // given
        RouteContext route = route(ROUTE_ID);
        Batch older = batchWithVehicles(route, now.minusSeconds(20));
        Batch newer = batchWithVehicles(route, now);
        publishRecord(older, now.minusSeconds(20), model);
        publishLegacy(newer, now, model);

        // when
        BoardSnapshot auto = snapshotOf(ForecastSourceSetting.AUTO, ROUTE_ID);

        // then
        assertThat(auto).isEqualTo(snapshotOf(ForecastSourceSetting.LEGACY, ROUTE_ID));
        assertThat(auto.observation().orElseThrow().batchId()).isEqualTo(newer.id());
        assertThat(boardOf(ForecastSourceSetting.AUTO, ROUTE_ID)).isEqualTo(boardOf(ForecastSourceSetting.LEGACY, ROUTE_ID));
    }

    @Test
    void 자동_선택은_새_출처의_배치가_더_최신이면_새_출처를_쓴다() {
        // given
        RouteContext route = route(ROUTE_ID);
        Batch older = batchWithVehicles(route, now.minusSeconds(20));
        Batch newer = batchWithVehicles(route, now);
        publishLegacy(older, now.minusSeconds(20), model);
        publishRecord(newer, now, model);

        // when
        BoardSnapshot auto = snapshotOf(ForecastSourceSetting.AUTO, ROUTE_ID);

        // then
        assertThat(auto).isEqualTo(snapshotOf(ForecastSourceSetting.RECORD, ROUTE_ID));
        assertThat(auto.observation().orElseThrow().batchId()).isEqualTo(newer.id());
        assertThat(auto.observation().orElseThrow().forecastSource()).isEqualTo(ForecastSource.RECORD);
        assertThat(boardOf(ForecastSourceSetting.AUTO, ROUTE_ID)).isEqualTo(boardOf(ForecastSourceSetting.RECORD, ROUTE_ID));
    }

    @Test
    void 자동_선택은_두_출처의_배치가_같으면_옛_출처를_쓴다() {
        // given
        RouteContext route = route(ROUTE_ID);
        publishBoth(batchWithVehicles(route, now), now);

        // when
        BoardSnapshot auto = snapshotOf(ForecastSourceSetting.AUTO, ROUTE_ID);

        // then
        assertThat(auto).isEqualTo(snapshotOf(ForecastSourceSetting.LEGACY, ROUTE_ID));
        assertThat(auto.observation().orElseThrow().forecastSource()).isEqualTo(ForecastSource.LEGACY);
    }

    @Test
    void 자동_선택은_한_출처에만_예보가_있으면_그_출처를_쓴다() {
        // given
        RouteContext recordOnly = route(ROUTE_ID);
        RouteContext legacyOnly = route(OTHER_ROUTE_ID);
        publishRecord(batchWithVehicles(recordOnly, now.minusSeconds(20)), now.minusSeconds(20), model);
        batchWithVehicles(recordOnly, now);
        publishLegacy(batchWithVehicles(legacyOnly, now.minusSeconds(20)), now.minusSeconds(20), model);
        batchWithVehicles(legacyOnly, now);

        // when
        BoardSnapshot recordAuto = snapshotOf(ForecastSourceSetting.AUTO, ROUTE_ID);
        BoardSnapshot legacyAuto = snapshotOf(ForecastSourceSetting.AUTO, OTHER_ROUTE_ID);

        // then
        assertThat(recordAuto).isEqualTo(snapshotOf(ForecastSourceSetting.RECORD, ROUTE_ID));
        assertThat(recordAuto.observation().orElseThrow().observedAt()).isEqualTo(now.minusSeconds(20));
        assertThat(legacyAuto).isEqualTo(snapshotOf(ForecastSourceSetting.LEGACY, OTHER_ROUTE_ID));
        assertThat(legacyAuto.observation().orElseThrow().observedAt()).isEqualTo(now.minusSeconds(20));
        assertThat(snapshotsOf(ForecastSourceSetting.AUTO, ROUTE_ID, OTHER_ROUTE_ID))
            .containsExactlyInAnyOrder(recordAuto, legacyAuto);
    }

    @Test
    void 자동_선택은_옛_출처의_발행이_신선도_창_밖이면_새_출처를_쓴다() {
        // given
        RouteContext route = route(ROUTE_ID);
        OffsetDateTime old = now.minusMinutes(10).minusSeconds(5);
        publishLegacy(batchWithVehicles(route, old), old, model);
        Batch fresh = batchWithVehicles(route, now);
        publishRecord(fresh, now, model);

        // when
        BoardSnapshot auto = snapshotOf(ForecastSourceSetting.AUTO, ROUTE_ID);

        // then
        assertThat(auto.observation().orElseThrow().batchId()).isEqualTo(fresh.id());
        assertThat(auto.observation().orElseThrow().forecastSource()).isEqualTo(ForecastSource.RECORD);
    }

    @Test
    void 본선을_새_경로로_바꾸는_순간_옛_발행_뒤의_첫_새_기록부터_자동_선택이_새_기록을_읽고_응답이_끊기지_않는다() {
        // given
        RouteContext route = route(ROUTE_ID);
        Batch shadowed = batchWithVehicles(route, now.minusSeconds(60));
        publishBoth(shadowed, now.minusSeconds(60));
        Batch lastLegacy = batchWithVehicles(route, now.minusSeconds(30));
        publishBoth(lastLegacy, now.minusSeconds(30));

        // when
        BoardSnapshot beforeFirstRecord = snapshotOf(ForecastSourceSetting.AUTO, ROUTE_ID);
        Board boardBeforeFirstRecord = boardOf(ForecastSourceSetting.AUTO, ROUTE_ID);
        Board legacyBoardBeforeFirstRecord = boardOf(ForecastSourceSetting.LEGACY, ROUTE_ID);
        Batch firstRecord = batchWithVehicles(route, now);
        publishRecord(firstRecord, now, model);
        BoardSnapshot afterFirstRecord = snapshotOf(ForecastSourceSetting.AUTO, ROUTE_ID);
        Board boardAfterFirstRecord = boardOf(ForecastSourceSetting.AUTO, ROUTE_ID);
        Board recordBoardAfterFirstRecord = boardOf(ForecastSourceSetting.RECORD, ROUTE_ID);
        Batch late = batchWithVehicles(route, now.plusSeconds(30));
        insertRun(late.id(), now.plusSeconds(121), model, "LATE", 0);
        BoardSnapshot afterLateRun = snapshotOf(ForecastSourceSetting.AUTO, ROUTE_ID);

        // then
        assertThat(beforeFirstRecord.observation().orElseThrow().batchId()).isEqualTo(lastLegacy.id());
        assertThat(beforeFirstRecord.observation().orElseThrow().forecastSource()).isEqualTo(ForecastSource.LEGACY);
        assertThat(boardBeforeFirstRecord).isEqualTo(legacyBoardBeforeFirstRecord);
        assertThat(vehicleAt(boardBeforeFirstRecord, 2, "BUS-A").forecast()).isInstanceOf(VehicleForecast.Available.class);
        assertThat(boardAfterFirstRecord).isEqualTo(recordBoardAfterFirstRecord);
        assertThat(vehicleAt(boardAfterFirstRecord, 2, "BUS-A").forecast()).isInstanceOf(VehicleForecast.Available.class);
        assertThat(afterFirstRecord.observation().orElseThrow().batchId()).isEqualTo(firstRecord.id());
        assertThat(afterFirstRecord.observation().orElseThrow().forecastSource()).isEqualTo(ForecastSource.RECORD);
        assertThat(afterLateRun.observation().orElseThrow().batchId()).isEqualTo(late.id());
        assertThat(afterLateRun.observation().orElseThrow().forecastSource()).isEqualTo(ForecastSource.RECORD);
        assertThat(afterLateRun).isEqualTo(snapshotOf(ForecastSourceSetting.RECORD, ROUTE_ID));
    }

    private Board boardOf(ForecastSourceSetting source, String routeId) {
        return serviceOf(source).readBoard(new RouteId(routeId)).board();
    }

    private BoardQueryService serviceOf(ForecastSourceSetting source) {
        return new BoardQueryService(sourceOf(source), freshnessPolicy, cachePolicy, new BoardReadCache(false, null));
    }

    private BoardSnapshot snapshotOf(ForecastSourceSetting source, String routeId) {
        Optional<BoardSnapshot> snapshot = sourceOf(source).findSnapshot(new RouteId(routeId));
        return snapshot.orElseThrow();
    }

    private List<BoardSnapshot> snapshotsOf(ForecastSourceSetting source, String... routeIds) {
        return sourceOf(source).findSnapshots(Stream.of(routeIds).map(RouteId::new).toList());
    }

    private ForecastSourceBoardQueryRepository sourceOf(ForecastSourceSetting source) {
        return new ForecastSourceBoardQueryRepository(jpaBoardQueryRepository, snapshotReader, recordReader, source);
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

    private RouteContext route(String routeId) {
        RouteContext route = fixture.insertRoute(routeId, "노선-" + routeId, "기점", "종점", null,
            "05:00", "23:00", null, null, now.minusDays(1));
        for (int sequence = 1; sequence <= STOP_COUNT; sequence++) {
            fixture.insertStop(route, sequence, "STOP-" + sequence, "정류장-" + sequence, "UP", true);
        }
        return route;
    }

    /**
     * 차량 세 대가 있는 배치다. A는 1번에서 목표 12개, B는 2번에서 목표 2개의 예측을 받고, 차량 번호가 없는 C는 예측이 없다.
     */
    private Batch batchWithVehicles(RouteContext route, OffsetDateTime receivedAt) {
        final long batch = fixture.insertBatch(route, receivedAt, "SUCCESS_ROWS", 3);
        final long busA = fixture.insertObservation(route, batch, 1, "BUS-A", 1, "STOP-1", receivedAt);
        final long busB = fixture.insertObservation(route, batch, 2, "BUS-B", 2, "STOP-2", receivedAt);
        fixture.insertObservation(route, batch, 3, null, 1, "STOP-1", receivedAt);
        return new Batch(route, batch, busA, busB);
    }

    private void publishBoth(Batch batch, OffsetDateTime at) {
        publishLegacy(batch, at, model);
        publishRecord(batch, at, model);
    }

    /** 옛 경로가 하차 반영 모델로 쓴 모양이다. 목표 5·6은 기대 좌석이 비어 있고 7은 무한대다. */
    private void publishLegacy(Batch batch, OffsetDateTime at, final long modelId) {
        fixture.insertPublication(batch.id(), modelId, at, at);
        productionRevisions(batch.id());
        for (int target = 2; target <= 13; target++) {
            fixture.insertForecast(batch.route(), batch.busA(), target, target - 1, modelId, chanceOf(target),
                legacySeatsOf(target), at);
        }
        fixture.insertForecast(batch.route(), batch.busB(), 3, 1, modelId, 0.4, 2.5, at);
        fixture.insertForecast(batch.route(), batch.busB(), 4, 2, modelId, 0.6, 1.0, at);
        jdbc.sql("""
            UPDATE seat_forecast SET seat_target='AFTER_ALIGHTING_ESTIMATE', probability_target='DEPARTURE_ZERO',
                label_version='alighting-identity-safe-v1', feature_snapshot='{}'::jsonb, inference_bundle_hash=?
            WHERE vehicle_observation_id IN (?, ?)
            """).params("b".repeat(64), batch.busA(), batch.busB()).update();
    }

    /** 새 경로가 같은 예측을 쓴 모양이다. 목표 5는 비어 있는 원소, 6은 NaN, 7은 무한대다. */
    private void publishRecord(Batch batch, OffsetDateTime at, final long modelId) {
        insertRun(batch.id(), at, modelId, "PUBLISHED", 14);
        StringBuilder chances = new StringBuilder();
        StringBuilder seats = new StringBuilder();
        StringBuilder targets = new StringBuilder();
        for (int target = 2; target <= 13; target++) {
            String separator = target == 2 ? "" : ",";
            targets.append(separator).append(target);
            chances.append(separator).append(chanceOf(target));
            seats.append(separator).append(recordSeatsOf(target));
        }
        insertRecord(batch.busA(), "{" + targets + "}", "{" + seats + "}", "{" + chances + "}");
        insertRecord(batch.busB(), "{3,4}", "{2.5,1.0}", "{0.4,0.6}");
    }

    private static double chanceOf(final int target) {
        return target / 20.0;
    }

    private static Double legacySeatsOf(final int target) {
        return switch (target) {
            case 5, 6 -> null;
            case 7 -> Double.POSITIVE_INFINITY;
            default -> 20.0 - target;
        };
    }

    private static String recordSeatsOf(final int target) {
        return switch (target) {
            case 5 -> "NULL";
            case 6 -> "NaN";
            case 7 -> "Infinity";
            default -> String.valueOf(20.0 - target);
        };
    }

    /** 운영의 옛 발행은 통계 판 0, 품질 판 1이다. 픽스처의 통계 판 1을 운영 값으로 맞춘다. */
    private void productionRevisions(final long batchId) {
        jdbc.sql("""
            UPDATE forecast_publication SET demand_statistics_revision=0, quality_revision=1 WHERE source_batch_id=?
            """).param(batchId).update();
    }

    private void insertRun(final long batchId, OffsetDateTime confirmedAt, Long modelId, String outcome,
        final int predictionCount) {
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

    private record Batch(RouteContext route, long id, long busA, long busB) {
    }
}
