package com.gustler.backend.api.route.controller;

import com.gustler.backend.api.route.application.RouteQueryService;
import com.gustler.backend.api.route.dto.RouteListResponse;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/routes")
public class RouteController {

    private static final CacheControl ROUTE_LIST_CACHE = CacheControl
        .maxAge(Duration.ofMinutes(5))
        .cachePublic();

    private final RouteQueryService routeQueryService;

    @GetMapping
    @Operation(summary = "현재 노선 목록", description = "현재 판본이 있는 노선을 반환합니다. 예보 상태는 노선마다 달라질 수 있습니다. 응답은 5분 공개 캐시가 가능합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "현재 판본이 있는 노선 목록",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RouteListResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.ROUTES))),
        @ApiResponse(responseCode = "503", description = "DB 등 조회 자원을 사용할 수 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.SERVICE_UNAVAILABLE))),
        @ApiResponse(responseCode = "405", description = "지원하지 않는 요청 방식",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.METHOD_NOT_ALLOWED))),
        @ApiResponse(responseCode = "500", description = "요청 처리 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INTERNAL_ERROR)))
    })
    public ResponseEntity<RouteListResponse> listRoutes() {
        RouteListResponse response = RouteListResponse.from(
            routeQueryService.getRouteOverview()
        );

        return ResponseEntity.ok()
            .cacheControl(ROUTE_LIST_CACHE)
            .body(response);
    }
}
