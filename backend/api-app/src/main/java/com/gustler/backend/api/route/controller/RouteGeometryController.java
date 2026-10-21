package com.gustler.backend.api.route.controller;

import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.RouteGeometryApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.application.RouteGeometryQueryService;
import com.gustler.backend.api.route.dto.RouteGeometryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * 지도 형상과 방문 판본을 제공하고 ETag를 이용한 재검증 요청을 처리한다.
 */
@RestController
@Tag(name = ApiDocsTags.ROUTES)
public class RouteGeometryController {
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().mustRevalidate();
    private final RouteGeometryQueryService service;

    public RouteGeometryController(RouteGeometryQueryService service) {
        this.service = service;
    }

    /**
     * 요청 판본과 ETag 조건을 적용해 형상을 반환한다. 현재 판본 충돌과 변경 없음 응답을 일반 형상 응답과 구별한다.
     */
    @GetMapping("/api/v2/routes/{routeId}/geometry")
    @Operation(operationId = "getRouteGeometry", summary = "노선 도로 형상과 정류장 좌표",
        description = """
            노선 목록에서 선택한 routeId로 한 번 조회합니다. `points`는 원본 운행 순서의 전체 도로 형상이고,
            `busStops[].point`는 정류장 마커 좌표입니다. 정류장 좌표를 이어 도로 형상을 만들지 않습니다.
            같은 stationId의 반복 방문은 `stopSequence`로 구분합니다. `turningPoint.stopSequence`도 방문 순번이며 형상 배열 인덱스가 아닙니다.

            `paths`가 있으면 `UP.points`와 `DOWN.points`를 함께 사용합니다. 두 경로는 회차 경계점을 공유하며,
            하행을 상행의 역순으로 만들지 않습니다. 방문 순서와 회차를 형상에 대응할 근거가 부족하거나 모호하면
            READY여도 `paths=null`일 수 있으므로 전체 `points`를 표시합니다. 방향 선택·색상·겹침 순서는 프론트에서 처리하고,
            방향 전환이나 지도 확대·이동마다 재조회하지 않습니다.

            READY는 도로 형상과 모든 정류장 좌표 제공, PARTIAL은 도로 형상을 유지하되 일부 정류장의 `point=null`인 상태입니다.
            UNAVAILABLE/NOT_COLLECTED는 현재 판본의 형상 미수집으로 `points=[]`, `paths=null`이며, 정류장 좌표가 있으면 마커만 표시할 수 있습니다.
            dev 공급 자료는 현재 41노선의 정류장 방문 3,215건과 형상점 35,542개를 포함한 GBIS 정적 재생 자료입니다.
            8노선은 기존 정적 API 저장본, 33노선은 공식 공개 지도 형상입니다. 방문 건수는 고유 물리 정류장 수가 아닙니다.
            초기 수집 완료 전이나 새 카탈로그 판본으로 바뀐 직후에는 미수집 상태일 수 있으며, dev 자료가 실시간 최신성을 보장하지는 않습니다.

            `referenceVersionId`를 생략하면 현재 판본을 반환합니다. 지정한 판본이 현재와 다르면 409이며 판본 없이 다시 조회합니다.
            새 판본에는 이전 판본의 정류장 좌표·형상을 붙이지 않습니다. READY/PARTIAL은 최대 5분 캐시와 ETag 재검증을 지원하며,
            같은 ETag에는 본문 없는 304를 반환합니다. UNAVAILABLE과 오류는 no-store이고, 저장소·저장 형상을 읽을 수 없으면 503입니다.
            조회는 외부 수집을 유발하지 않으며 모델·차량 관측의 준비 상태와 독립적입니다.
            """,
        parameters =
        @Parameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "이전 응답의 ETag. 일치하면 본문 없는 304",
            schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "READY: 형상과 모든 정류장 좌표. PARTIAL: 형상은 있고 일부 point=null. "
            + "UNAVAILABLE/NOT_COLLECTED: 현재 판본 형상 미수집. READY여도 paths=null 가능. 예시는 합성 자료.",
            headers = {
                @Header(name = "ETag", description = "W/\"g-{geometryRevision}\". UNAVAILABLE에는 제공하지 않음",
                    schema = @Schema(type = "string")),
                @Header(name = "Cache-Control",
                    description = "READY/PARTIAL은 public, max-age=300, must-revalidate. UNAVAILABLE은 no-store",
                    schema = @Schema(type = "string")),
                @Header(name = "Content-Encoding", description = "gzip 요청을 수락하고 본문이 2KB 이상이면 gzip 압축 가능",
                    schema = @Schema(type = "string"))
            },
            content =
            @Content(mediaType = "application/json", schema = @Schema(implementation = RouteGeometryResponse.class),
                examples = {
                    @ExampleObject(name = "ready", summary = "READY: 전체 형상·정류장·양방향 경로",
                        description = "합성 예시. 도로 형상과 정류장 좌표가 모두 있고 회차 분리 근거도 있는 경우입니다.",
                        value = RouteGeometryApiDocsExamples.READY),
                    @ExampleObject(name = "partial", summary = "PARTIAL: 형상 유지·정류장 좌표 일부 누락",
                        description = "합성 예시. point=null인 정류장의 마커를 생략하고 전체 points를 표시합니다. 회차 좌표 누락으로 paths=null입니다.",
                        value = RouteGeometryApiDocsExamples.PARTIAL),
                    @ExampleObject(name = "notCollected", summary = "UNAVAILABLE: 현재 판본 형상 미수집",
                        description = "합성 예시. points는 빈 배열, paths는 null입니다. 제공된 정류장 좌표로 마커만 표시할 수 있습니다.",
                        value = RouteGeometryApiDocsExamples.NOT_COLLECTED),
                    @ExampleObject(name = "withoutDirectionSplit", summary = "READY: 전체 형상 제공·방향 분리 근거 없음",
                        description = "합성 예시. READY가 paths의 존재를 보장하지 않습니다. paths=null이면 전체 points를 표시합니다.",
                        value = RouteGeometryApiDocsExamples.WITHOUT_DIRECTION_SPLIT)
                })),
        @ApiResponse(responseCode = "304", description = "ETag와 같은 지도 자료. 본문 없음", content = @Content,
            headers = {
                @Header(name = "ETag", description = "재검증한 ETag", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", description = "public, max-age=300, must-revalidate",
                    schema = @Schema(type = "string"))
            }),
        @ApiResponse(responseCode = "400", description = "노선 ID 또는 판본 형식 오류",
            content =
            @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "invalidRouteId", value = ApiDocsExamples.INVALID_ROUTE),
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST)
                })),
        @ApiResponse(responseCode = "404", description = "현재 카탈로그에 없는 노선",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.ROUTE_NOT_FOUND))),
        @ApiResponse(responseCode = "409",
            description = "지정한 판본이 현재 판본과 다름. referenceVersionId 없이 geometry를 다시 조회하고 새 방문 순번으로 선택을 갱신",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.REFERENCE_VERSION_CONFLICT))),
        @ApiResponse(responseCode = "503",
            description = "저장소 또는 저장된 형상 조회 불가. 형상 미수집을 뜻하는 200/UNAVAILABLE과 구분. no-store",
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
        @RequestParam(required = false) Long referenceVersionId,
        WebRequest request
    ) {
        RouteGeometryResponse response =
            RouteGeometryResponse.from(service.get(new RouteId(routeId), referenceVersionId));
        if (response.status().equals("UNAVAILABLE")) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
        }
        String etag = "W/\"g-" + response.geometryRevision() + "\"";
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(CACHE).eTag(etag).build();
        }
        return ResponseEntity.ok().cacheControl(CACHE).eTag(etag).body(response);
    }
}
