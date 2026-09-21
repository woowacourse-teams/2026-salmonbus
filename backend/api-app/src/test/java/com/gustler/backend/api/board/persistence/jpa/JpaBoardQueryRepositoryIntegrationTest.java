package com.gustler.backend.api.board.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.application.BoardVehicleObservation;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JpaBoardQueryRepositoryIntegrationTest {

    @Autowired
    private JpaBoardQueryRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 예보가_없어도_선택한_관측_묶음과_노선_판본의_차량을_조회한다() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now();
        RouteContext route = route(fixture, "204000057", now);
        RouteContext otherRoute = route(fixture, "234000050", now);
        long batch = fixture.insertBatch(route, now, now, "SUCCESS_ROWS", 2);
        long otherBatch = fixture.insertBatch(route, now.minusMinutes(1), now, "SUCCESS_ROWS", 1);
        long otherRouteBatch = fixture.insertBatch(otherRoute, now, now, "SUCCESS_ROWS", 1);
        long observation = fixture.insertObservation(route, batch, 1, null, 1, "STOP-1", now);
        fixture.insertObservation(route, batch, 2, null, 1, "STOP-1", now);
        fixture.insertObservation(route, otherBatch, 1, "OLD", 1, "STOP-1", now);
        fixture.insertObservation(otherRoute, otherRouteBatch, 1, "OTHER", 1, "STOP-1", now);
        // 정류장에 도착한 차량의 통과 순번은 현재 정류장 순번보다 하나 작다.
        jdbcClient.sql("UPDATE vehicle_observation SET passed_stop_order = 0, running_state = 1 WHERE id = :id")
            .param("id", observation).update();

        assertThat(repository.findObservedVehicles(batch, route.routeVersionId()))
            .containsExactlyInAnyOrder(new BoardVehicleObservation(null, 1, 0),
                new BoardVehicleObservation(null, 2, 1));
        assertThat(repository.findObservedVehicles(batch, otherRoute.routeVersionId())).isEmpty();
        assertThat(repository.findPredictions(batch)).isEmpty();
    }

    private RouteContext route(BoardDatabaseFixture fixture, String id, OffsetDateTime now) {
        RouteContext route = fixture.insertRoute(id, "노선", "기점", "종점", null,
            "05:00", "23:00", null, null, now.minusDays(1));
        fixture.insertStop(route, 1, "STOP-1", "기점", "UP", true);
        return route;
    }
}
