package com.gustler.backend.api.board.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.board.application.BoardCachePolicy;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@IntegrationTest
@Transactional
class BoardApiContractTest {

    private static final String ROUTE_ID = "204000057";

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
    void 최신_완료_poll로_v4_Board를_조립한다() throws Exception {
        final OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        final OffsetDateTime observedAt = now.minusMinutes(1);
        final RouteContext route = insertRoundTripRoute(now.minusDays(1));
        final long snapshotModelId = fixture.insertModel(
            "model-snapshot",
            "RETIRED",
            now.minusDays(2)
        );
        fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long completedBatchId = fixture.insertBatch(
            route,
            observedAt,
            observedAt.plusSeconds(2),
            "SUCCESS_ROWS",
            5
        );
        fixture.insertBatch(
            route,
            now,
            null,
            "SUCCESS_ROWS",
            1
        );
        insertPredictions(route, completedBatchId, snapshotModelId, observedAt);

        final String cacheControl = "max-age="
            + new BoardCachePolicy().maxAgeAt(observedAt).toSeconds()
            + ", public";

        mockMvc.perform(get("/api/v1/routes/{routeId}/board", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, cacheControl))
            .andExpect(jsonPath("$", aMapWithSize(5)))
            .andExpect(jsonPath("$.route", aMapWithSize(8)))
            .andExpect(jsonPath("$.route.id").value(ROUTE_ID))
            .andExpect(jsonPath("$.route.status").value("FORECAST_READY"))
            .andExpect(jsonPath("$.route.turnSequence").value(2))
            .andExpect(jsonPath("$.route.referenceVersionId")
                .value(String.valueOf(route.routeVersionId())))
            .andExpect(jsonPath("$.route.directions[0]", aMapWithSize(6)))
            .andExpect(jsonPath("$.route.directions[0].name").value("회차점 방면"))
            .andExpect(jsonPath("$.route.directions[1].name").value("종점 방면"))
            .andExpect(jsonPath("$.observedAt").value(observedAt.toString()))
            .andExpect(jsonPath("$.model", aMapWithSize(2)))
            .andExpect(jsonPath("$.model.releaseId").value("model-snapshot"))
            .andExpect(jsonPath("$.vehiclesInService").value(5))
            .andExpect(jsonPath("$.stops.length()").value(3))
            .andExpect(jsonPath("$.stops[0]", aMapWithSize(6)))
            .andExpect(jsonPath("$.stops[0].stationId").doesNotExist())
            .andExpect(jsonPath("$.stops[0].approachingVehicles").isEmpty())
            .andExpect(jsonPath("$.stops[1].boardingAllowed").value(false))
            .andExpect(jsonPath("$.stops[1].approachingVehicles").isEmpty())
            .andExpect(jsonPath("$.stops[2].approachingVehicles.length()").value(3))
            .andExpect(jsonPath("$.stops[2].approachingVehicles[0]", aMapWithSize(4)))
            .andExpect(jsonPath("$.stops[2].approachingVehicles[0].vehicleId")
                .value("B"))
            .andExpect(jsonPath("$.stops[2].approachingVehicles[0].seatAvailableProbability")
                .value(0.996))
            .andExpect(jsonPath("$.stops[2].approachingVehicles[1]", aMapWithSize(3)))
            .andExpect(jsonPath("$.stops[2].approachingVehicles[1].vehicleId").isEmpty())
            .andExpect(jsonPath("$.stops[2].approachingVehicles[1].expectedSeats")
                .doesNotExist())
            .andExpect(jsonPath("$.stops[2].approachingVehicles[2].vehicleId")
                .value("A"));
    }

    @Test
    void 완료_poll이_같은_시각이면_ID가_큰_poll을_선택한다() throws Exception {
        final OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        final OffsetDateTime observedAt = now.minusMinutes(1);
        final RouteContext route = insertRoundTripRoute(now.minusDays(1));
        final long firstModel = fixture.insertModel("model-first", "RETIRED", now.minusDays(2));
        final long secondModel = fixture.insertModel("model-second", "ACTIVE", now.minusDays(1));
        final long firstBatch = fixture.insertBatch(
            route,
            observedAt,
            observedAt.plusSeconds(1),
            "SUCCESS_ROWS",
            1
        );
        final long secondBatch = fixture.insertBatch(
            route,
            observedAt,
            observedAt.plusSeconds(2),
            "SUCCESS_ROWS",
            2
        );
        insertSinglePrediction(route, firstBatch, firstModel, "FIRST", observedAt);
        insertSinglePrediction(route, secondBatch, secondModel, "SECOND", observedAt);

        mockMvc.perform(get("/api/v1/routes/{routeId}/board", ROUTE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vehiclesInService").value(2))
            .andExpect(jsonPath("$.model.releaseId").value("model-second"))
            .andExpect(jsonPath("$.stops[2].approachingVehicles[0].vehicleId")
                .value("SECOND"));
    }

    @Test
    void ACTIVE_모델이_없으면_MODEL_OUT_OF_SCOPE_503이다() throws Exception {
        final OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        final RouteContext route = insertRoundTripRoute(now.minusDays(1));
        fixture.insertBatch(route, now.minusMinutes(1), now, "SUCCESS_EMPTY", 0);

        mockMvc.perform(get("/api/v1/routes/{routeId}/board", ROUTE_ID))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$", aMapWithSize(3)))
            .andExpect(jsonPath("$.code").value("MODEL_OUT_OF_SCOPE"));
    }

    @Test
    void 완료_poll이_없으면_NO_RECENT_OBSERVATION_503이다() throws Exception {
        final OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        insertRoundTripRoute(now.minusDays(1));
        fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));

        mockMvc.perform(get("/api/v1/routes/{routeId}/board", ROUTE_ID))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$", aMapWithSize(3)))
            .andExpect(jsonPath("$.code").value("NO_RECENT_OBSERVATION"));
    }

    @Test
    void 완료_poll이_5분을_넘으면_NO_RECENT_OBSERVATION_503이다() throws Exception {
        final OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        final RouteContext route = insertRoundTripRoute(now.minusDays(1));
        fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        fixture.insertBatch(
            route,
            now.minusMinutes(5).minusSeconds(1),
            now.minusMinutes(5),
            "SUCCESS_EMPTY",
            0
        );

        mockMvc.perform(get("/api/v1/routes/{routeId}/board", ROUTE_ID))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("NO_RECENT_OBSERVATION"));
    }

    @Test
    void routeId_형식이_잘못되면_INVALID_ROUTE_ID_400이다() throws Exception {
        mockMvc.perform(get("/api/v1/routes/abc/board"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$", aMapWithSize(3)))
            .andExpect(jsonPath("$.code").value("INVALID_ROUTE_ID"));
    }

    @Test
    void 현재_노선이_없으면_ROUTE_NOT_FOUND_404이다() throws Exception {
        mockMvc.perform(get("/api/v1/routes/999999999/board"))
            .andExpect(status().isNotFound())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$", aMapWithSize(3)))
            .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    private RouteContext insertRoundTripRoute(final OffsetDateTime validFrom) {
        final RouteContext route = fixture.insertRoute(
            ROUTE_ID,
            "3330",
            "기점",
            "종점",
            2,
            "04:50",
            "23:30",
            "05:00",
            "23:30",
            validFrom
        );
        fixture.insertStop(route, 1, "STOP-1", "기점", "UP", true);
        fixture.insertStop(route, 2, "STOP-2", "회차점", "UP", false);
        fixture.insertStop(route, 3, "STOP-3", "종점", "DOWN", true);
        return route;
    }

    private void insertPredictions(
        final RouteContext route,
        final long batchId,
        final long modelId,
        final OffsetDateTime observedAt
    ) {
        insertPrediction(route, batchId, modelId, 1, "C", 1, "STOP-1", 3, 2, 0.4, 12.0,
            observedAt);
        insertPrediction(route, batchId, modelId, 2, null, 2, "STOP-2", 3, 1, 0.2, null,
            observedAt);
        insertPrediction(route, batchId, modelId, 3, "A", 1, "STOP-1", 3, 2, 0.1, 20.0,
            observedAt);
        insertPrediction(route, batchId, modelId, 4, "B", 2, "STOP-2", 3, 1, 0.004, 15.0,
            observedAt);
        insertPrediction(
            route,
            batchId,
            modelId,
            5,
            "0",
            2,
            "STOP-2",
            3,
            1,
            0.5,
            Double.POSITIVE_INFINITY,
            observedAt
        );
        insertPrediction(route, batchId, modelId, 6, "IGNORED", 1, "STOP-1", 2, 1, 0.2,
            10.0, observedAt);
    }

    private void insertSinglePrediction(
        final RouteContext route,
        final long batchId,
        final long modelId,
        final String vehicleId,
        final OffsetDateTime observedAt
    ) {
        insertPrediction(route, batchId, modelId, 1, vehicleId, 2, "STOP-2", 3, 1, 0.2,
            10.0, observedAt);
    }

    private void insertPrediction(
        final RouteContext route,
        final long batchId,
        final long modelId,
        final int sourceRowNumber,
        final String vehicleId,
        final int currentStopOrder,
        final String currentStopId,
        final int targetStopOrder,
        final int horizonStops,
        final double pFull,
        final Double expectedSeats,
        final OffsetDateTime observedAt
    ) {
        final long observationId = fixture.insertObservation(
            route,
            batchId,
            sourceRowNumber,
            vehicleId,
            currentStopOrder,
            currentStopId,
            observedAt
        );
        fixture.insertForecast(
            route,
            observationId,
            targetStopOrder,
            horizonStops,
            modelId,
            pFull,
            expectedSeats,
            observedAt.plusSeconds(1)
        );
    }
}
