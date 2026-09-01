package com.gustler.backend.api.board.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 같은 관측 묶음을 볼 때 두 화면이 같은 차량을 보이는지 본다.
 *
 * <p>두 화면은 묶음을 서로 다른 조건으로 고른다. board 는 예보가 끝난 묶음만 보고 vehicles 는
 * 최신 묶음을 본다. 그래서 여기서는 묶음을 하나만 심어 둘이 같은 묶음을 보게 해 놓고 비교한다.
 */
@IntegrationTest
@Transactional
class BoardMatchesLiveVehiclesContractTest {

    private static final String ROUTE_ID = "204000057";
    private static final int STOP_COUNT = 6;

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Clock clock;

    private MockMvc mockMvc;
    private BoardDatabaseFixture fixture;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(applicationContext)
            .build();
        fixture = new BoardDatabaseFixture(jdbcClient);
    }

    @Test
    void 같은_묶음에서_보드와_차량_목록이_같은_차량을_보인다() throws Exception {
        OffsetDateTime observedAt = OffsetDateTime.now(clock).withNano(0).minusMinutes(1);
        RouteContext route = insertStraightRoute(observedAt.minusDays(1));
        final long modelId = fixture.insertModel("model-active", "ACTIVE", observedAt.minusDays(1));
        final long batchId = insertForecastCompletedBatch(route, observedAt, 3);
        insertForecastVehicle(route, batchId, modelId, 1, "예보있음", 1, observedAt);
        insertForecastVehicle(route, batchId, modelId, 2, "예보있음둘", 2, observedAt);
        fixture.insertObservation(route, batchId, 3, "예보없음", 3, "STOP-3", observedAt);

        assertThat(boardVehicleIds()).isEqualTo(liveVehicleIds());
    }

    @Test
    void 잔여석을_모르는_차량이_두_화면_모두에_나온다() throws Exception {
        OffsetDateTime observedAt = OffsetDateTime.now(clock).withNano(0).minusMinutes(1);
        RouteContext route = insertStraightRoute(observedAt.minusDays(1));
        fixture.insertModel("model-active", "ACTIVE", observedAt.minusDays(1));
        final long batchId = insertForecastCompletedBatch(route, observedAt, 1);
        fixture.insertObservation(route, batchId, 1, "좌석모름", 2, "STOP-2", observedAt);

        assertThat(boardVehicleIds()).containsExactlyElementsOf(liveVehicleIds());
    }

    @Test
    void 차량_id가_없는_관측이_두_화면_모두에_나온다() throws Exception {
        OffsetDateTime observedAt = OffsetDateTime.now(clock).withNano(0).minusMinutes(1);
        RouteContext route = insertStraightRoute(observedAt.minusDays(1));
        fixture.insertModel("model-active", "ACTIVE", observedAt.minusDays(1));
        final long batchId = insertForecastCompletedBatch(route, observedAt, 1);
        fixture.insertObservation(route, batchId, 1, null, 2, "STOP-2", observedAt);

        assertThat(boardVehicleCountAt(3)).isEqualTo(liveVehicleCount());
    }

    private Set<String> boardVehicleIds() throws Exception {
        List<String> vehicleIds = JsonPath.read(
            responseBodyOf("/api/v1/routes/{routeId}/board"),
            "$.stops[*].approachingVehicles[*].vehicleId"
        );
        return new HashSet<>(vehicleIds);
    }

    /**
     * 그 정류장에 실린 대수.
     *
     * <p>차량 id 가 없으면 보드 응답만으로는 차량을 서로 구분할 수 없다. 같은 차량이 정류장마다
     * 다른 거리로 실리기 때문에 전체를 훑어 세면 대수가 아니라 자리 수가 나온다. 그래서 정류장
     * 하나에서 센다.
     */
    private int boardVehicleCountAt(
        final int sequence
    ) throws Exception {
        List<Object> vehicles = JsonPath.read(
            responseBodyOf("/api/v1/routes/{routeId}/board"),
            "$.stops[%d].approachingVehicles[*]".formatted(sequence - 1)
        );
        return vehicles.size();
    }

    private int liveVehicleCount() throws Exception {
        List<Object> vehicles = JsonPath.read(
            responseBodyOf("/api/v1/routes/{routeId}/vehicles"),
            "$.vehicles[*]"
        );
        return vehicles.size();
    }

    private Set<String> liveVehicleIds() throws Exception {
        List<String> vehicleIds = JsonPath.read(
            responseBodyOf("/api/v1/routes/{routeId}/vehicles"),
            "$.vehicles[*].vehicleId"
        );
        return new HashSet<>(vehicleIds);
    }

    private String responseBodyOf(
        String path
    ) throws Exception {
        return mockMvc.perform(get(path, ROUTE_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    /** 여섯 정류장이 전부 승차 가능한 한 방향 노선. 어느 차량이든 앞에 실릴 정류장이 있다. */
    private RouteContext insertStraightRoute(
        OffsetDateTime validFrom
    ) {
        RouteContext route = fixture.insertRoute(
            ROUTE_ID,
            "3330",
            "기점",
            "종점",
            null,
            "04:50",
            "23:30",
            null,
            null,
            validFrom
        );
        for (int sequence = 1; sequence <= STOP_COUNT; sequence++) {
            fixture.insertStop(route, sequence, "STOP-" + sequence, "정류장" + sequence, "UP", true);
        }
        return route;
    }

    private long insertForecastCompletedBatch(
        RouteContext route,
        OffsetDateTime observedAt,
        final int storedRows
    ) {
        return fixture.insertBatch(
            route,
            observedAt,
            observedAt.plusSeconds(2),
            "SUCCESS_ROWS",
            storedRows
        );
    }

    private void insertForecastVehicle(
        RouteContext route,
        final long batchId,
        final long modelId,
        final int sourceRowNumber,
        String vehicleId,
        final int passedStopOrder,
        OffsetDateTime observedAt
    ) {
        final long observationId = fixture.insertObservation(
            route,
            batchId,
            sourceRowNumber,
            vehicleId,
            passedStopOrder,
            "STOP-" + passedStopOrder,
            observedAt
        );
        fixture.insertForecast(
            route,
            observationId,
            passedStopOrder + 1,
            1,
            modelId,
            0.2d,
            10.0,
            observedAt.plusSeconds(1)
        );
    }
}
