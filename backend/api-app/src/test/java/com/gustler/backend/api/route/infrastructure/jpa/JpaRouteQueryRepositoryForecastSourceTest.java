package com.gustler.backend.api.route.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JpaRouteQueryRepositoryForecastSourceTest {
    private static final String NO_FORECAST = "204000057";
    private static final String RECORDED = "234000051";

    @Autowired
    private JpaRouteQueryRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbc);
        OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        route(fixture, NO_FORECAST, now);
        BoardDatabaseFixture.RouteContext recorded = route(fixture, RECORDED, now);
        final long batch = fixture.insertBatch(recorded, now, "SUCCESS_EMPTY", 0);
        fixture.insertRun(batch, null, "EMPTY", now);
    }

    @Test
    void 예측이_0건이어도_실행_줄이_있는_노선을_예보_준비로_본다() {
        assertThat(repository.findForecastPublishedRouteIds()).containsExactly(RECORDED);
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
