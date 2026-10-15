package com.gustler.backend.api.route.controller;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.application.RouteGeometryQueryService;
import com.gustler.backend.api.route.dto.RouteGeometryResponse;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.RouteGeometryApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
public class RouteGeometryController {
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().mustRevalidate();
    private final RouteGeometryQueryService service;

    public RouteGeometryController(RouteGeometryQueryService service) { this.service = service; }

    @GetMapping("/api/v2/routes/{routeId}/geometry")
    @Operation(operationId = "getRouteGeometry", summary = "노선 경로와 정류장 좌표",
        description = "노선 선택 시 조회해 points로 전체 경로, busStops[].point로 정류장을 그립니다. "
            + "paths가 있으면 UP/DOWN을 별도 선으로 표시하고 방향 선택·색상·겹침 순서는 프론트에서 처리합니다. "
            + "방향 전환이나 지도 확대·이동마다 재조회하지 않습니다. 같은 stationId의 반복 방문은 stopSequence로 구분합니다. "
            + "판본을 생략하면 현재 판본을 반환하며, 지정한 판본이 현재와 다르면 409입니다. 과거 판본 조회가 아닙니다. "
            + "조회가 GBIS 수집을 유발하지 않으며 차량 좌표나 구간 진행률을 제공하는 API도 아닙니다.",
        parameters = @Parameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "이전 응답의 ETag. 일치하면 본문 없는 304",
            schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "전체 형상·일부 정류장 좌표 누락·형상 미수집 상태. paths는 별도로 null 확인",
            headers = {
                @Header(name = "ETag", description = "W/\"g-{geometryRevision}\". UNAVAILABLE에는 제공하지 않음", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", description = "READY/PARTIAL은 public, max-age=300, must-revalidate. UNAVAILABLE은 no-store",
                    schema = @Schema(type = "string")),
                @Header(name = "Content-Encoding", description = "gzip 요청을 수락하고 본문이 2KB 이상이면 gzip 압축 가능", schema = @Schema(type = "string"))
            },
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RouteGeometryResponse.class), examples = {
                @ExampleObject(name = "ready", value = RouteGeometryApiDocsExamples.READY),
                @ExampleObject(name = "partial", value = RouteGeometryApiDocsExamples.PARTIAL),
                @ExampleObject(name = "notCollected", value = RouteGeometryApiDocsExamples.NOT_COLLECTED),
                @ExampleObject(name = "withoutDirectionSplit", value = RouteGeometryApiDocsExamples.WITHOUT_DIRECTION_SPLIT)
            })),
        @ApiResponse(responseCode = "304", description = "ETag와 같은 지도 자료. 본문 없음", content = @Content,
            headers = {
                @Header(name = "ETag", description = "재검증한 ETag", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", description = "public, max-age=300, must-revalidate", schema = @Schema(type = "string"))
            }),
        @ApiResponse(responseCode = "400", description = "노선 ID 또는 판본 형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class), examples = {
                @ExampleObject(name = "invalidRouteId", value = ApiDocsExamples.INVALID_ROUTE),
                @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST)
            })),
        @ApiResponse(responseCode = "404", description = "현재 카탈로그에 없는 노선",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.ROUTE_NOT_FOUND))),
        @ApiResponse(responseCode = "409", description = "지정한 판본이 현재 판본과 다름. 판본 지정 없이 다시 조회",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.REFERENCE_VERSION_CONFLICT))),
        @ApiResponse(responseCode = "503", description = "저장소 또는 저장된 형상 조회 불가",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.SERVICE_UNAVAILABLE))),
        @ApiResponse(responseCode = "405", description = "지원하지 않는 HTTP 메서드",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.METHOD_NOT_ALLOWED))),
        @ApiResponse(responseCode = "500", description = "요청 처리 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INTERNAL_ERROR)))
    })
    public ResponseEntity<RouteGeometryResponse> get(
        @Parameter(description = "9자리 GBIS 노선 ID", example = "\"204000057\"", schema = @Schema(pattern = "^[0-9]{9}$"))
        @PathVariable String routeId,
        @Parameter(description = "현재 노선 판본. 양의 64비트 정수의 문자열 표현", example = "\"7\"",
            schema = @Schema(implementation = String.class, pattern = "^[1-9][0-9]*$"))
        @RequestParam(required = false) Long referenceVersionId, WebRequest request) {
        var response = RouteGeometryResponse.from(service.get(new RouteId(routeId), referenceVersionId));
        if (response.status().equals("UNAVAILABLE")) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
        String etag = "W/\"g-" + response.geometryRevision() + "\"";
        if (request.checkNotModified(etag)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(CACHE).eTag(etag).build();
        return ResponseEntity.ok().cacheControl(CACHE).eTag(etag).body(response);
    }
}
