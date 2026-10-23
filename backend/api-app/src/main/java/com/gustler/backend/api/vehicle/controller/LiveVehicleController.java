package com.gustler.backend.api.vehicle.controller;

import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.vehicle.application.LiveVehicleOverview;
import com.gustler.backend.api.vehicle.application.LiveVehicleQueryService;
import com.gustler.backend.api.vehicle.dto.LiveVehicleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 최신 차량 조회를 공개 응답으로 변환하고 관측의 캐시 정책을 HTTP 헤더로 전달한다.
 */
@RestController
@Tag(name = ApiDocsTags.ROUTES)
@RequestMapping("/api/v1/routes")
@RequiredArgsConstructor
public class LiveVehicleController {
    private final LiveVehicleQueryService liveVehicleQueryService;

    /**
     * 현재 노선 판본의 실시간 차량 화면을 반환한다. 관측 부재와 오래된 관측 상태를 응답에서 구별한다.
     */
    @GetMapping("/{routeId}/vehicles")
    @Operation(summary = "실시간 차량 관측",
        description = """
            현재 노선 판본 기준의 최신 차량 관측을 반환합니다. 관측 상태는 `observation.state`로 구분합니다.
            - VEHICLES_PRESENT: 차량 위치가 있음
            - NO_VEHICLES_OBSERVED: 최신 정상 관측에 차량이 없음. 운행 종료로 단정하지 않습니다
            - UNKNOWN: 관측이 없거나 실패했거나 만료됐거나 차량 행을 해석하지 못함. `vehicles`는 빈 배열입니다
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "현재 차량 관측 또는 관측 상태 UNKNOWN",
            headers = @Header(name = "Cache-Control",
                description = "public, max-age는 응답 시각대별 정책값(07~08시·17~22시 15초, 01~03시대 600초, 그 밖의 시간 20초)",
                schema = @Schema(type = "string")),
            content =
            @Content(mediaType = "application/json", schema = @Schema(implementation = LiveVehicleResponse.class),
                examples = {
                    @ExampleObject(name = "VEHICLES_PRESENT", value = ApiDocsExamples.VEHICLES_PRESENT),
                    @ExampleObject(name = "UNKNOWN", value = ApiDocsExamples.VEHICLES_UNKNOWN)
                })),
        @ApiResponse(responseCode = "400", description = "노선 ID 형식이 올바르지 않음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INVALID_ROUTE))),
        @ApiResponse(responseCode = "404", description = "등록되지 않은 노선",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.ROUTE_NOT_FOUND))),
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
    public ResponseEntity<LiveVehicleResponse> getLiveVehicles(
        @Parameter(description = "9자리 GBIS 노선 ID", example = "123456789")
        @PathVariable("routeId") String routeId
    ) {
        LiveVehicleOverview overview = liveVehicleQueryService.getLiveVehicles(
            new RouteId(routeId)
        );

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(overview.cacheMaxAge()).cachePublic())
            .body(LiveVehicleResponse.from(overview));
    }
}
