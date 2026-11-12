package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class BoardQueryServiceBatchReadIntegrationTest {
    @Autowired
    private BoardQueryService boards;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Clock clock;

    @Test
    void 실제_저장소에서_여러_노선을_한_번에_읽어도_노선마다_읽은_판정보드와_같다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        RouteContext published = route(fixture, "204000057", now);
        RouteContext observed = route(fixture, "234000050", now);
        final long publishedBatch = fixture.insertBatch(published, now, "SUCCESS_ROWS", 2);
        final long observedBatch = fixture.insertBatch(observed, now, "SUCCESS_ROWS", 1);
        fixture.insertPublication(publishedBatch, model, now, now);
        final long forecasted = fixture.insertObservation(published, publishedBatch, 1, "PUBLISHED", 1, "STOP-1", now);
        fixture.insertObservation(published, publishedBatch, 2, "PLAIN", 1, "STOP-1", now);
        fixture.insertObservation(observed, observedBatch, 1, "OBSERVED", 1, "STOP-1", now);
        fixture.insertForecast(published, forecasted, 2, 1, model, 0.2, 20.0, now);
        List<String> routeIds = List.of(published.sourceRouteId(), "999999999", observed.sourceRouteId());

        // when
        Map<String, BoardBatchRead> reads = boards.readBoards(routeIds, BoardReadDeadline.none());

        // then
        Board publishedBoard = reads.get(published.sourceRouteId()).reading().board();
        Board observedBoard = reads.get(observed.sourceRouteId()).reading().board();
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(reads.keySet()).containsExactlyElementsOf(routeIds);
            softly.assertThat(publishedBoard)
                .isEqualTo(boards.readBoard(new RouteId(published.sourceRouteId())).board());
            softly.assertThat(observedBoard)
                .isEqualTo(boards.readBoard(new RouteId(observed.sourceRouteId())).board());
            softly.assertThat(publishedBoard.stops().get(1).approachingVehicles())
                .extracting(vehicle -> vehicle.vehicleId())
                .containsExactly("PLAIN", "PUBLISHED");
            softly.assertThat(observedBoard.stops().get(1).approachingVehicles())
                .extracting(vehicle -> vehicle.vehicleId())
                .containsExactly("OBSERVED");
            softly.assertThat(reads.get("999999999").failure()).isInstanceOf(RouteNotFoundException.class);
        }
    }

    @Test
    void 활성_모델이_없으면_노선마다_준비_중으로_실패한다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        RouteContext route = route(fixture, "204000057", now);
        fixture.insertBatch(route, now, "SUCCESS_EMPTY", 0);

        // when
        Map<String, BoardBatchRead> reads = boards.readBoards(List.of(route.sourceRouteId()), BoardReadDeadline.none());

        // then
        assertThat(reads.get(route.sourceRouteId()).failure()).isInstanceOf(ModelOutOfScopeException.class);
    }

    private RouteContext route(BoardDatabaseFixture fixture, String id, OffsetDateTime now) {
        RouteContext route = fixture.insertRoute(id, "노선-" + id, "기점", "종점", null,
            "05:00", "23:00", null, null, now.minusDays(1));
        fixture.insertStop(route, 1, "STOP-1", "기점", "UP", true);
        fixture.insertStop(route, 2, "STOP-2", "종점", "UP", true);
        return route;
    }
}
