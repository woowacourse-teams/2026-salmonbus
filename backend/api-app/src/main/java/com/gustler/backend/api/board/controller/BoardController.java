package com.gustler.backend.api.board.controller;

import com.gustler.backend.api.board.application.BoardOverview;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.dto.BoardResponse;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.route.RouteId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routes/{routeId}/board")
@RequiredArgsConstructor
public class BoardController {

    private final BoardQueryService boardQueryService;

    @GetMapping
    @Operation(summary = "노선별 판정 보드", description = "발행된 예보가 있으면 해당 관측을, 없으면 최신 정상 관측을 기준으로 보드를 구성합니다. 신선도는 관측 후 5분입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "보드 상태별 정상 응답",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = BoardResponse.class), examples = {
                @ExampleObject(name = "FORECAST_READY", value = ApiDocsExamples.BOARD_READY),
                @ExampleObject(name = "PREPARING", value = ApiDocsExamples.BOARD_PREPARING)
            })),
        @ApiResponse(responseCode = "400", description = "노선 ID 형식이 올바르지 않음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INVALID_ROUTE))),
        @ApiResponse(responseCode = "404", description = "등록되지 않은 노선",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.ROUTE_NOT_FOUND))),
        @ApiResponse(responseCode = "503", description = "모델 범위 밖, 최근 정상 관측 없음 또는 DB 사용 불가",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class), examples = {
                @ExampleObject(name = "MODEL_OUT_OF_SCOPE", value = ApiDocsExamples.MODEL_OUT_OF_SCOPE),
                @ExampleObject(name = "NO_RECENT_OBSERVATION", value = ApiDocsExamples.NO_RECENT_OBSERVATION),
                @ExampleObject(name = "SERVICE_UNAVAILABLE", value = ApiDocsExamples.SERVICE_UNAVAILABLE)
            })),
        @ApiResponse(responseCode = "405", description = "지원하지 않는 요청 방식",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.METHOD_NOT_ALLOWED))),
        @ApiResponse(responseCode = "500", description = "요청 처리 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INTERNAL_ERROR)))
    })
    public ResponseEntity<BoardResponse> getBoard(
        @Parameter(description = "9자리 GBIS 노선 ID", example = "123456789")
        @PathVariable String routeId
    ) {
        BoardOverview overview = boardQueryService.getBoard(new RouteId(routeId));

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(overview.cacheMaxAge()).cachePublic())
            .body(BoardResponse.from(overview.board()));
    }
}
