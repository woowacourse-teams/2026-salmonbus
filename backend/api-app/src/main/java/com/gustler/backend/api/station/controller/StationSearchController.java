package com.gustler.backend.api.station.controller;

import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.StationMapApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.station.application.StationSearchService;
import com.gustler.backend.api.station.dto.StationSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정류장 이름 검색. /api/v2/stations/{stationId}보다 구체적인 경로라 상세 컨트롤러로 가지 않는다.
 */
@RestController
@Tag(name = ApiDocsTags.ARRIVALS)
public class StationSearchController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();
    private final StationSearchService service;

    public StationSearchController(StationSearchService service) {
        this.service = service;
    }

    @GetMapping("/api/v2/stations/search")
    @Operation(
        operationId = "searchStations",
        summary = "정류장 이름 검색",
        description = "정류장 이름에 검색어가 들어 있는 정류장을 최대 30개 줍니다. 공백과 기호, 영문 대소문자는 구별하지 않습니다. "
            + "이름이 검색어와 같은 정류장, 검색어로 시작하는 정류장, 검색어를 포함하는 정류장 순이며 "
            + "같은 순위에서는 우리 노선 정류장이 먼저입니다. 정류장을 누르면 /api/v2/stations/{stationId}를 부릅니다."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "검색된 정류장. 없으면 빈 목록",
            headers = @Header(
                name = "Cache-Control",
                description = "public, max-age=300",
                schema = @Schema(type = "string")
            ),
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = StationSearchResponse.class),
                examples = @ExampleObject(value = StationMapApiDocsExamples.SEARCH)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "검색어가 없거나, 공백과 기호를 빼면 비거나, 그렇게 뺀 글자가 40자를 넘음",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = StationMapApiDocsExamples.INVALID_SEARCH)
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
    public ResponseEntity<StationSearchResponse> search(
        @Parameter(description = "정류장 이름 검색어. 공백과 기호를 뺀 글자 1~40자", example = "판교역")
        @RequestParam(name = "q", required = false) final String q
    ) {
        return ResponseEntity.ok()
            .cacheControl(CACHE)
            .body(StationSearchResponse.from(service.search(q)));
    }
}
