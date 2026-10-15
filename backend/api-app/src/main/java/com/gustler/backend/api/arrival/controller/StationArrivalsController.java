package com.gustler.backend.api.arrival.controller;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.dto.StationArrivalsResponse;
import com.gustler.backend.api.docs.ApiDocsExamples;
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
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StationArrivalsController {
    private final ArrivalQueryService service;
    public StationArrivalsController(ArrivalQueryService service) { this.service = service; }

    @GetMapping("/api/v2/stations/{stationId}/arrivals")
    @Operation(operationId = "getStationArrivals", summary = "정류장 도착정보",
        description = "선택 정류장까지의 ETA를 반환합니다. routeId는 노선을 필터링하고, referenceVersionId와 stopSequence는 "
            + "routeId와 함께 전달해 같은 정류장의 반복 방문을 구분합니다. 판본·순번 중 하나만 전달하면 400입니다. "
            + "미수집·만료·호출 제한도 200 본문의 데이터 상태와 refresh 상태로 구별합니다. 같은 정류장은 수집 결과를 공유하며 "
            + "40초 후 갱신 대상, 60초 후 만료입니다. 자동 구독은 없고 refresh.suggestedRefreshAt에 맞춰 재조회합니다. "
            + "HTTP 캐시 최대 5초는 폴링 주기가 아닙니다. 카운트다운은 Date/Age를 반영한 서버 기준시각과 expectedArrivalAt을 "
            + "사용하고, 예상 시각 경과나 staleAt 도달을 실제 도착으로 확정하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "도착정보 또는 수집 중·예측 부재·제공 불가 상태",
            headers = @Header(name = "Cache-Control", description = "유효한 결과는 public, max-age 최대 5초. UNAVAILABLE은 no-store",
                schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StationArrivalsResponse.class), examples = {
                @ExampleObject(name = "ready", value = ArrivalApiDocsExamples.READY),
                @ExampleObject(name = "collecting", value = ArrivalApiDocsExamples.COLLECTING),
                @ExampleObject(name = "noPrediction", value = ArrivalApiDocsExamples.NO_PREDICTION),
                @ExampleObject(name = "stale", value = ArrivalApiDocsExamples.STALE)
            })),
        @ApiResponse(responseCode = "400", description = "정류장·노선 ID 형식 또는 필터 조합 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INVALID_REQUEST))),
        @ApiResponse(responseCode = "404", description = "지원하지 않는 정류장 또는 노선",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class), examples = {
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
            schema = @Schema(minimum = "1")) @RequestParam(required = false) Integer stopSequence) {
        var overview = service.get(new ArrivalQuery(stationId, routeId, referenceVersionId, stopSequence));
        CacheControl cache = CacheControl.noStore();
        if (overview.status() != ArrivalAvailability.UNAVAILABLE) {
            cache = CacheControl.maxAge(overview.cacheMaxAge()).cachePublic();
        }
        return ResponseEntity.ok().cacheControl(cache).body(StationArrivalsResponse.from(overview));
    }
}
