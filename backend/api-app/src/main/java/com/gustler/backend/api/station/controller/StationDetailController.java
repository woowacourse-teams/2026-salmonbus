package com.gustler.backend.api.station.controller;

import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.StationMapApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.station.application.StationDetailOverview;
import com.gustler.backend.api.station.application.StationDetailService;
import com.gustler.backend.api.station.dto.StationDetailResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 정류장 상세. 정류장으로 오는 모든 버스와 우리 노선의 좌석 예보를 한 번에 준다.
 */
@RestController
@Tag(name = ApiDocsTags.ARRIVALS)
public class StationDetailController {

    private final StationDetailService service;

    public StationDetailController(StationDetailService service) {
        this.service = service;
    }

    @GetMapping("/api/v2/stations/{stationId}")
    @Operation(
        operationId = "getStationDetail",
        summary = "정류장 상세 — 도착 버스와 우리 노선 예보",
        description = "정류장 지도에서 누른 정류장의 도착정보입니다. 우리 노선은 supported=true이고 차량별 좌석 예보가 붙습니다. "
            + "시트가 열려 있는 동안 refresh.suggestedRefreshAt에 다시 부르고 닫히면 멈춥니다."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "정류장 상세·도착정보 수신 상태·우리 노선 예보",
            headers = @Header(
                name = "Cache-Control",
                description = "유효한 도착정보는 public, max-age 최대5초. UNAVAILABLE은 no-store",
                schema = @Schema(type = "string")
            ),
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = StationDetailResponse.class),
                examples = @ExampleObject(name = "collecting", value = StationMapApiDocsExamples.DETAIL)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "잘못된 정류장 ID 형식",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INVALID_REQUEST)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "등록된 활성 정류장 또는 우리 노선의 정류장을 찾지 못함",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.STATION_NOT_FOUND)
            )
        ),
        @ApiResponse(
            responseCode = "405",
            description = "지원하지 않는 HTTP 메서드",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.METHOD_NOT_ALLOWED)
            )
        ),
        @ApiResponse(
            responseCode = "503",
            description = "저장소 연결 또는 조회 불가",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.SERVICE_UNAVAILABLE)
            )
        ),
        @ApiResponse(
            responseCode = "500",
            description = "요청 처리 실패",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INTERNAL_ERROR)
            )
        )
    })
    public ResponseEntity<StationDetailResponse> get(
        @Parameter(
            description = "등록된 GBIS 정류장의9자리 ID",
            example = "\"206000532\"",
            schema = @Schema(pattern = "^[0-9]{9}$")
        ) @PathVariable String stationId
    ) {
        StationDetailOverview overview = service.get(stationId);
        CacheControl cache = CacheControl.noStore();
        if (overview.arrivals().status() != ArrivalAvailability.UNAVAILABLE) {
            cache = CacheControl.maxAge(overview.cacheMaxAge()).cachePublic();
        }
        return ResponseEntity.ok().cacheControl(cache).body(StationDetailResponse.from(overview));
    }
}
