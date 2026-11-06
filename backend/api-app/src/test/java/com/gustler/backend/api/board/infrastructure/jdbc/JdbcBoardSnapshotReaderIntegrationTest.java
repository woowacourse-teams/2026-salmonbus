package com.gustler.backend.api.board.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.infrastructure.jpa.BoardForecastPublicationEntityRepository;
import com.gustler.backend.api.board.infrastructure.jpa.BoardObservationBatchEntityRepository;
import com.gustler.backend.api.board.infrastructure.jpa.BoardRouteStopEntityRepository;
import com.gustler.backend.api.board.infrastructure.jpa.BoardRouteVersionEntityRepository;
import com.gustler.backend.api.board.infrastructure.jpa.BoardVehicleObservationEntityRepository;
import com.gustler.backend.api.board.infrastructure.jpa.JpaBoardQueryRepository;
import com.gustler.backend.api.board.infrastructure.jpa.SeatForecastEntityRepository;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.observation.ObservationFreshness;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.infrastructure.jpa.ModelDeploymentEntityRepository;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcBoardSnapshotReaderIntegrationTest {

    @Autowired
    private JdbcBoardSnapshotReader reader;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationContext context;

    private BoardDatabaseFixture fixture;

    private JpaBoardQueryRepository legacy;

    @BeforeEach
    void setUp() {
        fixture = new BoardDatabaseFixture(jdbc);
        legacy = new JpaBoardQueryRepository(
            clock,
            context.getBean(BoardRouteVersionEntityRepository.class),
            context.getBean(BoardObservationBatchEntityRepository.class),
            context.getBean(BoardRouteStopEntityRepository.class),
            context.getBean(SeatForecastEntityRepository.class),
            context.getBean(BoardVehicleObservationEntityRepository.class),
            context.getBean(ModelDeploymentEntityRepository.class),
            context.getBean(BoardForecastPublicationEntityRepository.class),
            reader
        );
        ReflectionTestUtils.setField(legacy, "singleQueryEnabled", false);
    }

    @Test
    void 등록되지_않은_노선은_두_경로_모두_없다() {
        RouteId id = new RouteId("999999999");
        assertThat(reader.findSnapshot(id)).isEmpty().isEqualTo(legacy.findSnapshot(id));
    }

    @Test
    void 관측과_모델이_없어도_노선과_시간표는_동일하게_읽는다() {
        BoardDatabaseFixture.RouteContext route = route();
        jdbc.sql("""
            UPDATE route_version SET weekday_min_headway_minutes=10, weekday_max_headway_minutes=20,
                saturday_min_headway_minutes=15, saturday_max_headway_minutes=25,
                sunday_min_headway_minutes=20, sunday_max_headway_minutes=35,
                holiday_min_headway_minutes=30, holiday_max_headway_minutes=40 WHERE id=?
            """).param(route.routeVersionId()).update();
        BoardSnapshot snapshot = same(route);
        assertThat(snapshot.observation()).isEmpty();
        assertThat(snapshot.activeModel()).isEmpty();
        assertThat(snapshot.revision()).isNull();
        assertThat(snapshot.schedule().headways().weekday().maxMinutes()).isEqualTo(20);
        assertThat(snapshot.route().displayName()).isEqualTo("스냅숏 검증 노선");
    }

    @Test
    void 처음_발행하면_더_최신의_미발행_회차보다_발행된_회차를_선택한다() {
        BoardDatabaseFixture.RouteContext route = route();
        OffsetDateTime now = now();
        final long older = fixture.insertBatch(route, now.minusSeconds(20), "SUCCESS_EMPTY", 0);
        final long newer = fixture.insertBatch(route, now, "SUCCESS_EMPTY", 0);
        assertThat(same(route).observation().orElseThrow().batchId()).isEqualTo(newer);
        final long model = fixture.insertModel("active", "ACTIVE", now.minusDays(1));
        fixture.insertPublication(older, model, now, now);
        BoardSnapshot firstPublication = same(route);
        assertThat(firstPublication.observation().orElseThrow().batchId()).isEqualTo(older);
        assertThat(firstPublication.observation().orElseThrow().forecastPublished()).isTrue();
        fixture.insertPublication(newer, model, now, now);
        assertThat(same(route).observation().orElseThrow().batchId()).isEqualTo(newer);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void 같은_수신_시각이면_배치_ID가_큰_회차를_선택한다(final boolean published) {
        BoardDatabaseFixture.RouteContext route = route();
        OffsetDateTime now = now();
        final long first = fixture.insertBatch(route, now, "SUCCESS_EMPTY", 0);
        final long second = fixture.insertBatch(route, now, "SUCCESS_EMPTY", 0);
        if (published) {
            final long model = fixture.insertModel("active", "ACTIVE", now.minusDays(1));
            fixture.insertPublication(first, model, now, now.plusSeconds(1));
            fixture.insertPublication(second, model, now, now);
        }
        assertThat(same(route).observation().orElseThrow().batchId()).isEqualTo(second);
    }

    @Test
    void 최신_관측이_있어도_발행된_회차가_조회_창_밖이면_선택하지_않는다() {
        BoardDatabaseFixture.RouteContext route = route();
        OffsetDateTime now = now();
        OffsetDateTime old = now.minus(ObservationFreshness.MAX_WINDOW).minusSeconds(5);
        final long batch = fixture.insertBatch(route, old, "SUCCESS_EMPTY", 0);
        final long model = fixture.insertModel("active", "ACTIVE", now.minusDays(1));
        fixture.insertPublication(batch, model, old, old);
        fixture.insertBatch(route, now, "SUCCESS_EMPTY", 0);
        assertThat(same(route).observation()).isEmpty();
    }

    @Test
    void 발행_시각보다_관측_수신_시각으로_최신을_선택한다() {
        BoardDatabaseFixture.RouteContext route = route();
        OffsetDateTime now = now();
        final long model = fixture.insertModel("active", "ACTIVE", now.minusDays(1));
        final long earlier = fixture.insertBatch(route, now.minusSeconds(20), "SUCCESS_EMPTY", 0);
        final long later = fixture.insertBatch(route, now.minusSeconds(10), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(earlier, model, now, now.plusSeconds(2));
        fixture.insertPublication(later, model, now, now);
        assertThat(same(route).observation().orElseThrow().batchId()).isEqualTo(later);
    }

    @Test
    void 품질_행이_없어도_관측과_모델을_읽는다() {
        BoardDatabaseFixture.RouteContext route = route();
        OffsetDateTime now = now();
        fixture.insertBatch(route, now, "SUCCESS_EMPTY", 0);
        fixture.insertModel("active", "ACTIVE", now.minusDays(1));
        jdbc.sql("DELETE FROM route_data_quality WHERE route_id=?").param(route.routeId()).update();
        BoardSnapshot snapshot = same(route);
        assertThat(snapshot.revision()).isEqualTo("q-|p-|g-|n-|m-|d-|r-");
        assertThat(snapshot.activeModel()).isPresent();
    }

    @Test
    void 실패한_관측은_더_최근이어도_선택하지_않는다() {
        BoardDatabaseFixture.RouteContext route = route();
        OffsetDateTime now = now();
        final long normal = fixture.insertBatch(route, now.minusSeconds(10), "SUCCESS_EMPTY", 0);
        fixture.insertBatch(route, now, "FAILED_UNREADABLE", null);
        assertThat(same(route).observation().orElseThrow().batchId()).isEqualTo(normal);
    }

    private BoardSnapshot same(BoardDatabaseFixture.RouteContext route) {
        RouteId id = new RouteId(route.sourceRouteId());
        assertThat(reader.findSnapshot(id)).isEqualTo(legacy.findSnapshot(id));
        return reader.findSnapshot(id).orElseThrow();
    }

    private BoardDatabaseFixture.RouteContext route() {
        return fixture.insertRoute(
            "204000057",
            "스냅숏 검증 노선",
            "기점",
            "종점",
            2,
            "05:00",
            "23:00",
            "05:10",
            "23:10",
            now().minusDays(1)
        );
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withNano(0);
    }
}
