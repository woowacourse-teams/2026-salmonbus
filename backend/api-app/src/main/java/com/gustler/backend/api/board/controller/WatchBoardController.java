package com.gustler.backend.api.board.controller;

import com.gustler.backend.api.board.application.WatchBoardOverview;
import com.gustler.backend.api.board.application.WatchBoardQueryService;
import com.gustler.backend.api.board.dto.WatchBoardResponse;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.route.RouteId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
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
@RequestMapping("/api/v1/routes/{routeId}/watch")
@RequiredArgsConstructor
public class WatchBoardController {

    private final WatchBoardQueryService watchBoardQueryService;

    @GetMapping
    @Operation(operationId = "getRouteWatch", summary = "노선별 판정 보드와 차량 위치",
        description = "발행된 예보가 있으면 해당 관측을, 없으면 최신 정상 관측을 기준으로 보드를 구성하고, 같은 관측의 차량 위치를 "
            + "함께 반환합니다. 화면이 주기적으로 다시 부르는 폴링용 조회입니다. 200 응답은 전체가 관측 하나에서 나온 것입니다. 신선도는 관측 후 5분, 관측 시각이 01~03시대면 10분입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "보드 상태별 정상 응답",
            headers = @Header(name = "Cache-Control",
                description = "public, max-age는 관측 시각대별 정책값(첨두 15초, 평시 20초, 01~03시대 600초)과 "
                    + "staleAt까지 남은 시간 중 작은 값. staleAt을 넘겨 캐시하지 않는다", schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WatchBoardResponse.class), examples = {
                @ExampleObject(name = "FORECAST_READY", value = ApiDocsExamples.WATCH_BOARD_READY),
                @ExampleObject(name = "PREPARING", value = ApiDocsExamples.WATCH_BOARD_PREPARING)
            })),
        @ApiResponse(responseCode = "400", description = "노선 ID 형식이 올바르지 않음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INVALID_ROUTE))),
        @ApiResponse(responseCode = "404", description = "등록되지 않은 노선",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.ROUTE_NOT_FOUND))),
        @ApiResponse(responseCode = "503", description = "모델 범위 밖, 최근 정상 관측 없음 또는 DB 사용 불가. Cache-Control은 no-store",
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
    public ResponseEntity<WatchBoardResponse> getWatchBoard(
        @Parameter(description = "9자리 GBIS 노선 ID", example = "123456789")
        @PathVariable String routeId
    ) {
        WatchBoardOverview overview = watchBoardQueryService.getWatchBoard(new RouteId(routeId));

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(overview.cacheMaxAge()).cachePublic())
            .body(WatchBoardResponse.from(overview));
    }
}
