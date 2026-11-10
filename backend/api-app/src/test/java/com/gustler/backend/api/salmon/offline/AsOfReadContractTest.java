package com.gustler.backend.api.salmon.offline;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.infrastructure.jpa.JpaBoardQueryRepository;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import com.gustler.backend.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 판정 시각 기준 읽기 어댑터가 제품 저장소와 같은 규칙으로 읽는지 본다. 시계를 지금에 두면 두 구현이 같은 값을 돌려줘야 하고,
 * 시계를 과거에 두면 그 시각 뒤에 생긴 자료가 보이지 않아야 한다.
 */
@IntegrationTest
@Transactional
class AsOfReadContractTest {
    private static final String ROUTE = "204000057";
    private static final String STATION = "228000015";

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JpaBoardQueryRepository productBoards;
    @Autowired
    private ArrivalReadRepository productArrivals;

    @Test
    void 시계가_지금이면_발행된_묶음과_그_정류장_예보_차량을_제품_저장소와_똑같이_읽는다() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbc);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = routeWithStops(fixture, now);
        long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        long earlier = fixture.insertBatch(route, now.minusMinutes(2), "SUCCESS_ROWS", 1);
        fixture.insertPublication(earlier, model, now.minusSeconds(110), now.minusSeconds(100));
        long latest = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_ROWS", 3);
        fixture.insertPublication(latest, model, now.minusSeconds(50), now.minusSeconds(40));
        long near = fixture.insertObservation(route, latest, 1, "bus-1", 2, "STOP-2", now);
        long far = fixture.insertObservation(route, latest, 2, "bus-2", 1, "STOP-1", now);
        long excluded = fixture.insertObservation(route, latest, 3, "bus-3", 1, "STOP-1", now);
        fixture.insertForecast(route, near, 3, 1, model, 0.2, 20.0, now.minusSeconds(50));
        fixture.insertForecast(route, far, 3, 2, model, 0.4, null, now.minusSeconds(50));
        fixture.insertForecast(route, excluded, 3, 2, model, 0.6, 5.0, now.minusSeconds(50));
        jdbc.sql("UPDATE vehicle_one_way_trip SET status = 'EXCLUDED' WHERE start_observation_id = ?")
            .param(excluded).update();
        fixture.insertBatch(route, now, "SUCCESS_ROWS", 1);

        AsOfBoardQueryRepository boards = new AsOfBoardQueryRepository(jdbc, new AsOfClock(Instant.now()));

        BoardSnapshot expected = productBoards.findSnapshot(new RouteId(ROUTE)).orElseThrow();
        assertThat(expected.observation().orElseThrow().batchId()).isEqualTo(latest);
        assertThat(boards.findSnapshot(new RouteId(ROUTE))).contains(expected);
        assertThat(boards.findStops(route.routeVersionId()))
            .isEqualTo(productBoards.findStops(route.routeVersionId()));
        assertThat(boards.findPredictions(latest)).hasSize(2)
            .containsExactlyInAnyOrderElementsOf(productBoards.findPredictions(latest));
        assertThat(boards.findObservedVehicles(latest, route.routeVersionId())).hasSize(3)
            .containsExactlyInAnyOrderElementsOf(productBoards.findObservedVehicles(latest, route.routeVersionId()));
    }

    @Test
    void 시계가_지금이면_발행이_없는_판본의_묶음도_제품_저장소와_똑같이_고른다() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbc);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = routeWithStops(fixture, now);
        fixture.insertBatch(route, now.minusMinutes(2), "SUCCESS_ROWS", 1);
        long latest = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);
        fixture.insertBatch(route, now.minusSeconds(30), "FAILED_UNREADABLE", null);

        AsOfBoardQueryRepository boards = new AsOfBoardQueryRepository(jdbc, new AsOfClock(Instant.now()));

        BoardSnapshot expected = productBoards.findSnapshot(new RouteId(ROUTE)).orElseThrow();
        assertThat(expected.observation().orElseThrow().batchId()).isEqualTo(latest);
        assertThat(expected.activeModel()).isEmpty();
        assertThat(boards.findSnapshot(new RouteId(ROUTE))).contains(expected);
    }

    @Test
    void 판정_시각까지_발행된_묶음_가운데_관측이_가장_늦은_것을_고른다() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbc);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = routeWithStops(fixture, now);
        long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        long first = fixture.insertBatch(route, now.minusMinutes(4), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(first, model, now.minusSeconds(235), now.minusSeconds(230));
        long second = fixture.insertBatch(route, now.minusMinutes(2), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(second, model, now.minusSeconds(70), now.minusSeconds(60));
        AsOfClock clock = new AsOfClock(now.toInstant());
        AsOfBoardQueryRepository boards = new AsOfBoardQueryRepository(jdbc, clock);

        assertThat(batchAt(boards)).isEqualTo(second);

        // 둘째 묶음은 관측이 판정 시각보다 앞이지만 발행이 뒤라서, 그 시각의 보드는 아직 첫째 묶음을 본다.
        clock.moveTo(now.minusSeconds(90).toInstant());
        assertThat(batchAt(boards)).isEqualTo(first);

        clock.moveTo(now.minusMinutes(5).toInstant());
        BoardSnapshot beforeAnyBatch = boards.findSnapshot(new RouteId(ROUTE)).orElseThrow();
        assertThat(beforeAnyBatch.observation()).isEmpty();
    }

    @Test
    void 판정_시각까지_발행이_하나도_없던_판본은_그때의_가장_늦은_정상_묶음을_발행_없는_관측으로_고른다() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbc);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = routeWithStops(fixture, now);
        long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        long raw = fixture.insertBatch(route, now.minusMinutes(6), "SUCCESS_EMPTY", 0);
        long published = fixture.insertBatch(route, now.minusMinutes(2), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(published, model, now.minusSeconds(70), now.minusSeconds(60));
        long later = fixture.insertBatch(route, now.minusSeconds(30), "SUCCESS_EMPTY", 0);
        AsOfClock clock = new AsOfClock(now.minusMinutes(5).toInstant());
        AsOfBoardQueryRepository boards = new AsOfBoardQueryRepository(jdbc, clock);

        // 첫 발행이 판정 시각보다 뒤라서, 그 시각의 보드는 이 판본을 발행 없는 판본으로 본다.
        BoardSnapshot beforeFirstPublication = boards.findSnapshot(new RouteId(ROUTE)).orElseThrow();
        assertThat(beforeFirstPublication.observation().orElseThrow().batchId()).isEqualTo(raw);
        assertThat(beforeFirstPublication.observation().orElseThrow().forecastPublished()).isFalse();

        // 발행이 생긴 뒤에는 더 늦은 미발행 묶음이 있어도 발행된 묶음을 고른다. 판정 시각 뒤의 묶음은 보지 않는다.
        clock.moveTo(now.minusSeconds(45).toInstant());
        assertThat(batchAt(boards)).isEqualTo(published);
        clock.moveTo(now.toInstant());
        assertThat(batchAt(boards)).isEqualTo(published);
        assertThat(later).isGreaterThan(published);
    }

    @Test
    void 발행이_없는_판본에서도_판정_시각_뒤에_수신한_묶음은_고르지_않는다() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbc);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = routeWithStops(fixture, now);
        long earlier = fixture.insertBatch(route, now.minusMinutes(3), "SUCCESS_EMPTY", 0);
        fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);
        AsOfBoardQueryRepository boards = new AsOfBoardQueryRepository(jdbc,
            new AsOfClock(now.minusMinutes(2).toInstant()));

        assertThat(batchAt(boards)).isEqualTo(earlier);
    }

    @Test
    void 물러난_모델은_물러난_시각부터_활성이_아니고_활성_시각이_빈_활성_모델은_제품처럼_활성으로_본다() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbc);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        routeWithStops(fixture, now);
        long retired = fixture.insertModel("model-retired", "RETIRED", now.minusHours(2));
        jdbc.sql("UPDATE model_deployment SET retired_at = ? WHERE id = ?").params(now.minusHours(1), retired)
            .update();
        long active = fixture.insertModel("model-active", "ACTIVE", now.minusMinutes(30));
        AsOfClock clock = new AsOfClock(now.minusMinutes(90).toInstant());
        AsOfBoardQueryRepository boards = new AsOfBoardQueryRepository(jdbc, clock);

        assertThat(modelAt(boards)).contains(retired);
        clock.moveTo(now.minusMinutes(45).toInstant());
        assertThat(modelAt(boards)).isEmpty();
        clock.moveTo(now.toInstant());
        assertThat(modelAt(boards)).contains(active);

        jdbc.sql("UPDATE model_deployment SET activated_at = NULL WHERE id = ?").param(active).update();
        assertThat(productBoards.findSnapshot(new RouteId(ROUTE)).orElseThrow().activeModel()).isPresent();
        assertThat(boards.findSnapshot(new RouteId(ROUTE)))
            .isEqualTo(productBoards.findSnapshot(new RouteId(ROUTE)));
    }

    @Test
    void 판정_시각에_유효하던_노선_판본과_그때_활성이던_모델을_고른다() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbc);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext previous = routeWithStops(fixture, now);
        RouteContext current = fixture.insertNextVersion(previous, now.minusMinutes(1));
        fixture.insertStop(current, 7, STATION, "옮긴 정류장", "UP", true);
        long model = fixture.insertModel("model-active", "ACTIVE", now.minusHours(1));
        AsOfClock clock = new AsOfClock(now.toInstant());
        AsOfBoardQueryRepository boards = new AsOfBoardQueryRepository(jdbc, clock);
        AsOfArrivalReadRepository arrivals = new AsOfArrivalReadRepository(jdbc, json, clock);

        BoardSnapshot atNow = boards.findSnapshot(new RouteId(ROUTE)).orElseThrow();
        assertThat(atNow.routeVersionId()).isEqualTo(current.routeVersionId());
        assertThat(atNow.activeModel().orElseThrow().deploymentId()).isEqualTo(model);
        assertThat(arrivals.visits(STATION)).isEqualTo(productArrivals.visits(STATION));
        assertThat(arrivals.visits(STATION)).singleElement()
            .satisfies(visit -> assertThat(visit.stopSequence()).isEqualTo(7));

        clock.moveTo(now.minusHours(2).toInstant());
        BoardSnapshot earlier = boards.findSnapshot(new RouteId(ROUTE)).orElseThrow();
        assertThat(earlier.routeVersionId()).isEqualTo(previous.routeVersionId());
        assertThat(earlier.activeModel()).isEmpty();
        assertThat(arrivals.visits(STATION)).singleElement().satisfies(visit -> {
            assertThat(visit.referenceVersionId()).isEqualTo(previous.routeVersionId());
            assertThat(visit.stopSequence()).isEqualTo(3);
        });
    }

    @Test
    void 시계가_지금이면_가장_최근_도착_스냅샷을_제품_저장소와_똑같이_읽고_과거면_그때까지_수신한_것만_읽는다() {
        Instant now = Instant.now();
        ArrivalSnapshot older = snapshotReceivedAt(now.minusSeconds(50));
        ArrivalSnapshot newer = snapshotReceivedAt(now.minusSeconds(10));
        AsOfClock clock = new AsOfClock(now);
        AsOfArrivalReadRepository arrivals = new AsOfArrivalReadRepository(jdbc, json, clock);

        assertThat(productArrivals.latest(STATION)).contains(newer);
        assertThat(arrivals.latest(STATION)).contains(newer);

        clock.moveTo(now.minusSeconds(30));
        assertThat(arrivals.latest(STATION)).contains(older);

        clock.moveTo(now.minusSeconds(60));
        assertThat(arrivals.latest(STATION)).isEmpty();
    }

    private static Optional<Long> modelAt(AsOfBoardQueryRepository boards) {
        return boards.findSnapshot(new RouteId(ROUTE)).orElseThrow().activeModel()
            .map(model -> model.deploymentId());
    }

    private static long batchAt(AsOfBoardQueryRepository boards) {
        return boards.findSnapshot(new RouteId(ROUTE)).orElseThrow().observation().orElseThrow().batchId();
    }

    private RouteContext routeWithStops(BoardDatabaseFixture fixture, OffsetDateTime now) {
        RouteContext route = fixture.insertRoute(ROUTE, "3330", "기점", "종점", null, "05:00", "23:00", null, null,
            now.minusDays(1));
        fixture.insertStop(route, 1, "STOP-1", "첫 정류장", "UP", true);
        fixture.insertStop(route, 2, "STOP-2", "둘째 정류장", "UP", true);
        fixture.insertStop(route, 3, STATION, "셋째 정류장", "UP", true);
        return route;
    }

    private ArrivalSnapshot snapshotReceivedAt(Instant received) {
        ArrivalSnapshot snapshot = new ArrivalSnapshot(UUID.randomUUID(), STATION, received,
            received.plusSeconds(40), received.plusSeconds(60), null,
            List.of(new RouteArrival(ROUTE, "3330", 3, null, null, null, null,
                List.of(new Prediction("bus-1", 1, 120, null, null, null, "NOT_REPORTED", null, null, null)))));
        jdbc.sql("""
            INSERT INTO arrival_snapshot(id, provider, operation, station_id, received_at, refresh_after_at,
                stale_at, normalization_version, payload_digest, payload)
            VALUES (?, 'GBIS', 'ARRIVAL_LIST_V2', ?, ?, ?, ?, 'fixture', ?, CAST(? AS jsonb))
            """).params(snapshot.id(), STATION, Timestamp.from(received), Timestamp.from(snapshot.refreshAfterAt()),
            Timestamp.from(snapshot.staleAt()), "a".repeat(64), json.writeValueAsString(snapshot)).update();
        return snapshot;
    }
}
