package com.gustler.backend.api.route.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JpaRouteQueryRepositoryForecastSourceTest {
    private static final String NO_FORECAST = "204000057";
    private static final String LEGACY_ONLY = "234000050";
    private static final String RECORD_ONLY = "234000051";

    @Autowired
    private RouteEntityRepository routeRepository;

    @Autowired
    private ModelDeploymentEntityRepository modelDeploymentRepository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbc);
        OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        route(fixture, NO_FORECAST, now);
        BoardDatabaseFixture.RouteContext legacyOnly = route(fixture, LEGACY_ONLY, now);
        BoardDatabaseFixture.RouteContext recordOnly = route(fixture, RECORD_ONLY, now);
        fixture.insertPublication(fixture.insertBatch(legacyOnly, now, "SUCCESS_EMPTY", 0), model, now, now);
        final long batch = fixture.insertBatch(recordOnly, now, "SUCCESS_EMPTY", 0);
        jdbc.sql("""
            INSERT INTO forecast_run (source_date, observation_batch_id, route_id, route_version_id, received_at,
                confirmed_at, model_deployment_id, outcome, prediction_count)
            SELECT (batch.scheduled_at AT TIME ZONE 'Asia/Seoul')::date, batch.id, version.route_id,
                batch.route_version_id, batch.response_received_at, batch.response_received_at, NULL, 'EMPTY', 0
            FROM observation_batch batch
            JOIN route_version version ON version.id = batch.route_version_id
            WHERE batch.id = ?
            """).param(batch).update();
    }

    @Test
    void 옛_출처는_발행_표시가_있는_노선만_예보_준비로_본다() {
        assertThat(publishedRouteIds("legacy")).containsExactly(LEGACY_ONLY);
    }

    @Test
    void 새_출처는_예측이_0건이어도_실행_줄이_있는_노선을_예보_준비로_본다() {
        assertThat(publishedRouteIds("record")).containsExactly(RECORD_ONLY);
    }

    @Test
    void 자동_선택은_두_출처_중_하나라도_있는_노선을_예보_준비로_본다() {
        assertThat(publishedRouteIds("auto")).containsExactlyInAnyOrder(LEGACY_ONLY, RECORD_ONLY);
    }

    private Set<String> publishedRouteIds(String source) {
        JpaRouteQueryRepository repository = new JpaRouteQueryRepository(routeRepository, modelDeploymentRepository);
        repository.setForecastSource(source);
        return repository.findForecastPublishedRouteIds();
    }

    private static BoardDatabaseFixture.RouteContext route(
        BoardDatabaseFixture fixture,
        String routeId,
        OffsetDateTime now
    ) {
        return fixture.insertRoute(routeId, "노선-" + routeId, "기점", "종점", null,
            "05:00", "23:00", null, null, now.minusDays(1));
    }
}
