package com.gustler.backend.api.station.controller;

import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.StationMapApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.station.application.StationTileQueryService;
import com.gustler.backend.api.station.domain.StationTile;
import com.gustler.backend.api.station.dto.StationTileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * 정류장 지도의 줌 14 칸 조회. 저장 목록은 5분 캐시·ETag를 주고 갱신 접수는 응답과 분리한다.
 */
@RestController
@Tag(name = ApiDocsTags.ARRIVALS)
public class StationTileController {

    private static final CacheControl READY = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().mustRevalidate();
    private static final CacheControl PENDING = CacheControl.noStore();
    private final StationTileQueryService service;

    public StationTileController(StationTileQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/v2/stations/tiles/{zoom}/{x}/{y}")
    @Operation(
        operationId = "getStationTile",
        summary = "정류장 지도 칸",
        description = "웹 메르카토르 줌 14 칸의 정류장을 줍니다. 지도를 줌 14 이상으로 볼 때 화면에 걸친 칸만 부릅니다. "
            + "도착정보는 넣지 않으며 정류장을 누르면 /api/v2/stations/{stationId}를 부릅니다."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "기초 명부 또는 저장 목록. 원천 갱신 완료는 기다리지 않음",
            headers = {
                @Header(
                    name = "Cache-Control",
                    description = "READY: public, max-age=300, must-revalidate. PENDING: no-store",
                    schema = @Schema(type = "string")
                ),
                @Header(
                    name = "ETag",
                    description = "READY 목록과 기준일·검증 시각의 약한 ETag",
                    schema = @Schema(type = "string")
                )
            },
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = StationTileResponse.class),
                examples = {
                    @ExampleObject(name = "baseline", value = StationMapApiDocsExamples.BASELINE),
                    @ExampleObject(name = "pending", value = StationMapApiDocsExamples.PENDING)
                }
            )
        ),
        @ApiResponse(
            responseCode = "304",
            description = "READY 응답의 If-None-Match가 현재 ETag와 같음. 본문 없음",
            headers = @Header(
                name = "Cache-Control",
                description = "public, max-age=300, must-revalidate",
                schema = @Schema(type = "string")
            ),
            content = @Content
        ),
        @ApiResponse(
            responseCode = "400",
            description = "줌이14가 아니거나 칸 번호가0~16383을 벗어남",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = StationMapApiDocsExamples.INVALID_TILE)
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
    public ResponseEntity<StationTileResponse> get(
        @PathVariable final int zoom,
        @PathVariable final int x,
        @PathVariable final int y,
        WebRequest request
    ) {
        StationTile tile = service.get(zoom, x, y);
        StationTileResponse body = StationTileResponse.from(tile);
        if (!tile.ready()) {
            return ResponseEntity.ok().cacheControl(PENDING).body(body);
        }
        String etag = "W/\"t-" + digestOf(body) + "\"";
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(READY).eTag(etag).build();
        }
        return ResponseEntity.ok().cacheControl(READY).eTag(etag).body(body);
    }

    private static String digestOf(StationTileResponse body) {
        StringBuilder text =
            new StringBuilder().append(body.baselineDate()).append('|').append(body.verifiedAt()).append('\n');
        for (StationTileResponse.Station station : body.stations()) {
            text.append(station.stationId())
                .append('|')
                .append(station.name())
                .append('|')
                .append(station.point().x())
                .append('|')
                .append(station.point().y())
                .append('|')
                .append(station.mobileNo())
                .append('|')
                .append(station.regionName())
                .append('|')
                .append(station.direction())
                .append('|')
                .append(station.salmonRouteIds())
                .append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
