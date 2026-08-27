package com.gustler.backend.api.board.controller;

import static org.mockito.BDDMockito.given;
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
        given(boardQueryService.getBoard(any())).willReturn(overview());

        mockMvc.perform(get("/api/v1/routes/204000057/board"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=15, public"))
            .andExpect(jsonPath("$.route.id").value("204000057"))
            .andExpect(jsonPath("$.route.turnSequence").isEmpty())
            .andExpect(jsonPath("$.route.directions.length()").value(1))
            .andExpect(jsonPath("$.model.releaseId").value("model-7"))
            .andExpect(jsonPath("$.vehiclesInService").value(1))
            .andExpect(jsonPath("$.stops[0].stopId").value("STOP-1"))
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].vehicleId").isEmpty())
            .andExpect(jsonPath("$.stops[0].approachingVehicles[0].expectedSeats")
                .doesNotExist());
    }

    private BoardOverview overview() {
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
            List.of(new ApproachingVehicle(null, 1, 0.8, null))
        );
        Board board = new Board(
            route,
            OffsetDateTime.parse("2026-08-27T08:00:00+09:00"),
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
