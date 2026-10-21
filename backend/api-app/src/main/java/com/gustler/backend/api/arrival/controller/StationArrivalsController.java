package com.gustler.backend.api.arrival.controller;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.dto.StationArrivalsResponse;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.ArrivalApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정류장 도착정보 요청을 조회 조건으로 바꾸고, 결과의 유효 시간에 맞춰 HTTP 캐시 헤더를 제공한다.
 */
@RestController
@Tag(name = ApiDocsTags.ARRIVALS)
public class StationArrivalsController {
    private final ArrivalQueryService service;

    public StationArrivalsController(ArrivalQueryService service) {
        this.service = service;
    }

    /**
     * 정류장·노선·방문 순번·판본 조건으로 도착정보를 조회하고 수신 신선도에 맞는 캐시 헤더를 설정한다.
     */
    @GetMapping("/api/v2/stations/{stationId}/arrivals")
    @Operation(operationId = "getStationArrivals", summary = "정류장 도착정보",
        description = """
            선택 정류장까지의 ETA를 반환합니다.
            - 필터: `routeId`로 노선을 고릅니다. 같은 정류장의 반복 방문은 `referenceVersionId`와 `stopSequence`를 \
            `routeId`와 함께 보내 구분합니다. 둘 중 하나만 보내거나 `routeId` 없이 보내면 400입니다.
            - 상태: 미수집·만료·호출 제한도 200이며 본문의 `status`와 `refresh`로 구분합니다.
            - 갱신: 같은 정류장은 수집 결과를 공유합니다. 수신 후 40초부터 갱신 대상, 60초에 만료입니다. \
            자동 구독은 없으므로 `refresh.suggestedRefreshAt`에 맞춰 다시 조회합니다.
            - 첫 조회: 사용 가능한 수신이 없으면 기본 최대 500ms 동안 수집 완료를 기다리고, \
            계속 수집 중이면 1초 뒤 재조회를 안내합니다.
            - 캐시: HTTP 캐시 최대 5초는 폴링 주기가 아닙니다.
            - 카운트다운: Date/Age를 반영한 서버 기준 시각과 `expectedArrivalAt`을 씁니다. \
            예상 시각이 지나거나 `staleAt`에 이르러도 실제 도착으로 확정하지 않습니다.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "도착정보 또는 수집 중·예측 부재·제공 불가 상태",
            headers =
            @Header(name = "Cache-Control", description = "유효한 결과는 public, max-age 최대 5초. UNAVAILABLE은 no-store",
                schema = @Schema(type = "string")),
            content =
            @Content(mediaType = "application/json",
                schema = @Schema(implementation = StationArrivalsResponse.class), examples = {
                    @ExampleObject(name = "ready", value = ArrivalApiDocsExamples.READY),
                    @ExampleObject(name = "collecting", value = ArrivalApiDocsExamples.COLLECTING),
                    @ExampleObject(name = "noPrediction", value = ArrivalApiDocsExamples.NO_PREDICTION),
                    @ExampleObject(name = "stale", value = ArrivalApiDocsExamples.STALE)
                })),
        @ApiResponse(responseCode = "400", description = "정류장·노선 ID 형식 또는 필터 조합 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INVALID_REQUEST))),
        @ApiResponse(responseCode = "404", description = "지원하지 않는 정류장 또는 노선",
            content =
            @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "stationNotFound", value = ApiDocsExamples.STATION_NOT_FOUND),
                    @ExampleObject(name = "routeNotFound", value = ApiDocsExamples.ROUTE_NOT_FOUND)
                })),
        @ApiResponse(responseCode = "409", description = "현재 노선 판본·방문과 불일치. geometry를 다시 조회하고 방문을 재확인",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.REFERENCE_VERSION_CONFLICT))),
        @ApiResponse(responseCode = "503", description = "저장소 연결 또는 조회 불가",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.SERVICE_UNAVAILABLE))),
        @ApiResponse(responseCode = "405", description = "지원하지 않는 HTTP 메서드",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.METHOD_NOT_ALLOWED))),
        @ApiResponse(responseCode = "500", description = "요청 처리 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INTERNAL_ERROR)))
    })
    public ResponseEntity<StationArrivalsResponse> get(
        @Parameter(description = "9자리 정류장 ID", example = "\"206000637\"", schema = @Schema(pattern = "^[0-9]{9}$"))
        @PathVariable String stationId,
        @Parameter(description = "선택 노선의 9자리 ID. 생략하면 수집된 여러 노선의 도착정보", example = "\"204000057\"",
            schema = @Schema(pattern = "^[0-9]{9}$")) @RequestParam(required = false) String routeId,
        @Parameter(description = "현재 노선 판본의 양의 64비트 정수. routeId 및 stopSequence와 함께 사용", example = "7",
            schema = @Schema(minimum = "1")) @RequestParam(required = false) Long referenceVersionId,
        @Parameter(description = "geometry에서 선택한 정류장 방문 순번. referenceVersionId와 함께 사용", example = "22",
            schema = @Schema(minimum = "1")) @RequestParam(required = false) Integer stopSequence
    ) {
        StationArrivalsOverview overview =
            service.get(new ArrivalQuery(stationId, routeId, referenceVersionId, stopSequence));
        CacheControl cache = CacheControl.noStore();
        if (overview.status() != ArrivalAvailability.UNAVAILABLE) {
            cache = CacheControl.maxAge(overview.cacheMaxAge()).cachePublic();
        }
        return ResponseEntity.ok().cacheControl(cache).body(StationArrivalsResponse.from(overview));
    }
}
