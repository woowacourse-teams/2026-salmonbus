package com.gustler.backend.api.board.controller;

import static org.mockito.BDDMockito.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.board.application.BoardOverview;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.DirectionInfo;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.http.ApiExceptionHandler;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BoardControllerTest {

    private BoardQueryService boardQueryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        boardQueryService = mock(BoardQueryService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new BoardController(boardQueryService))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    @Test
    void v4_Board와_적응형_캐시를_반환한다() throws Exception {
        given(boardQueryService.getBoard(any())).willReturn(overview(new VehicleForecast.Available(0.8, null)));

        mockMvc.perform(get("/api/v1/routes/204000057/board"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=15, public"))
            .andExpect(jsonPath("$.route.id").value("204000057"))
            .andExpect(jsonPath("$.route.turnSequence").isEmpty())
            .andExpect(jsonPath("$.route.directions.length()").value(1))
            .andExpect(jsonPath("$.observedAt").value("2026-08-27T08:00:00+09:00"))
            .andExpect(jsonPath("$.staleAt").value("2026-08-27T08:05:00+09:00"))
            .andExpect(jsonPath("$.model.releaseId").value("model-7"))
            .andExpect(jsonPath("$.vehiclesInService").value(1))
            .andExpect(jsonPath("$.stops[0].stopId").value("STOP-1"))
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].vehicleId").isEmpty())
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].expectedSeats")
                .doesNotExist());
    }

    @Test
    void 접근_차량_응답에_forecast_필드를_포함한다() throws Exception {
        given(boardQueryService.getBoard(any())).willReturn(overview(new VehicleForecast.Available(0.8, null)));

        mockMvc.perform(get("/api/v1/routes/204000057/board"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0]", aMapWithSize(3)))
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].forecast", aMapWithSize(2)))
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].forecast.expectedSeats").doesNotExist())
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].seatAvailableProbability").doesNotExist());
    }

    @Test
    void 예보가_있으면_forecast에_AVAILABLE과_예측값을_포함한다() throws Exception {
        given(boardQueryService.getBoard(any())).willReturn(overview(new VehicleForecast.Available(0.0, 0.0)));

        mockMvc.perform(get("/api/v1/routes/204000057/board"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].forecast", aMapWithSize(3)))
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].forecast.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].forecast.seatAvailableProbability").value(0.0))
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].forecast.expectedSeats").value(0.0));
    }

    @Test
    void 예보가_없으면_forecast에_UNAVAILABLE만_포함한다() throws Exception {
        given(boardQueryService.getBoard(any())).willReturn(overview(new VehicleForecast.Unavailable()));

        mockMvc.perform(get("/api/v1/routes/204000057/board"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].forecast", aMapWithSize(1)))
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].forecast.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].forecast.seatAvailableProbability").doesNotExist())
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].forecast.expectedSeats").doesNotExist());
    }

    private BoardOverview overview(VehicleForecast forecast) {
        BoardRoute route = new BoardRoute(
            "204000057",
            "3330",
            "기점",
            "종점",
            RouteStatus.FORECAST_READY,
            null,
            List.of(new DirectionInfo(
                BoardDirection.UP,
                "종점 방면",
                "기점",
                "종점",
                "04:50",
                "23:30"
            )),
            "1"
        );
        StopState stop = new StopState(
            1,
            "STOP-1",
            "기점",
            BoardDirection.UP,
            true,
            List.of(new ApproachingVehicle(null, 1, forecast))
        );
        Board board = new Board(
            route,
            OffsetDateTime.parse("2026-08-27T08:00:00+09:00"),
            OffsetDateTime.parse("2026-08-27T08:05:00+09:00"),
            new ForecastModel(
                7L,
                "model-7",
                OffsetDateTime.parse("2026-08-26T23:59:59+09:00")
            ),
            1,
            List.of(stop)
        );
        return new BoardOverview(board, Duration.ofSeconds(15));
    }
}
