package com.gustler.backend.api.boarding.controller;

import com.gustler.backend.api.boarding.BoardingOptionsException;
import com.gustler.backend.api.boarding.application.BoardingOptionsQuery;
import com.gustler.backend.api.boarding.application.BoardingOptionsQueryService;
import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.boarding.dto.BoardingOptionsResponse;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.route.RouteId;
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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * 같은 노선의 다른 정류장으로 이동해 탈 수 있는 후보와 그곳까지 가는 방법을 제공한다.
 * 지금은 정적 도보 거리만 쓰므로 5분 캐시와 ETag 재검증을 지원한다.
 */
@RestController
@Tag(name = ApiDocsTags.ROUTES)
public class BoardingOptionsController {
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().mustRevalidate();
    private final BoardingOptionsQueryService service;

    public BoardingOptionsController(BoardingOptionsQueryService service) {
        this.service = service;
    }

    /**
     * 조건을 검증해 후보를 계산하고 응답 내용으로 만든 약한 ETag를 붙인다. 같은 ETag에는 본문 없는 304를 준다.
     */
    @GetMapping("/api/v1/routes/{routeId}/stations/{stationId}/boarding-options")
    @Operation(operationId = "getBoardingOptions", summary = "같은 노선에서 이동해 탈 수 있는 정류장 후보",
        description = """
            기준 정류장에서 같은 노선의 다른 정류장으로 이동해 탈 수 있는 후보를 가장 빠른 이동 방법의 시간 순서로 반환합니다.
            후보는 정류장 방문 하나이고, `stop`(어디서 탈지)과 `accesses`(거기까지 가는 방법 목록, 빠른 순서)를 둡니다.
            이동 방법 하나는 구간 목록 `legs`이고, `durationSeconds`는 출발부터 탑승 정류장 도착까지 전체 시간입니다.
            기다리는 시간이 있는 수단이 생기면 그 시간도 들어가 구간 시간의 합보다 클 수 있습니다.
            지금은 `modes=WALK`만 지원하므로 후보마다 이동 방법이 하나, 구간이 도보 하나입니다.
            `modes`의 기본값은 앞으로도 `WALK`입니다. 새 수단은 `modes`에 넣어 요청할 때만 응답에 나옵니다.
            이동 수단이 늘어도 `limit`은 정류장 수이고 응답 모양은 같습니다. 구간 수단별 칸은 수단이 생길 때 추가됩니다.
            `mode`·`source`·`relation`은 값이 늘어날 수 있으므로 모르는 값의 이동 방법이나 후보는 건너뜁니다.

            도보 거리는 OpenStreetMap 보행 그래프로 미리 계산한 실제 보행 경로 거리(© OpenStreetMap contributors)이며
            직선거리가 아닙니다. 도보 시간은 거리를 1.06m/s로 나눈 값입니다. 정류장이 25m 넘게 옮겨진 쌍은 후보에서 뺍니다.
            `relation`은 기준과의 관계입니다. `BEFORE`·`AFTER`는 같은 방향에서 같은 회차의 버스가 기준보다 먼저·나중에 지나는
            정류장, `OPPOSITE`는 방향이 다른 정류장, `SAME_STATION`은 같은 정류장의 다른 방문입니다.
            `OPPOSITE`는 방향이 다르다는 뜻만 있고, 그 정류장에서 타고 기준 쪽으로 돌아올 수 있는지는 보장하지 않습니다.
            `sequenceOffset`은 후보 순번 − 기준 순번입니다.
            같은 정류장을 두 번 지나는 노선은 `stopSequence`로 기준 방문을 고릅니다.
            """,
        parameters =
        @Parameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "이전 응답의 ETag. 일치하면 본문 없는 304",
            schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "후보 목록. 없으면 options=[]",
            headers = {
                @Header(name = "ETag", description = "W/\"b-{내용 해시}\"", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", description = "public, max-age=300, must-revalidate",
                    schema = @Schema(type = "string"))
            },
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = BoardingOptionsResponse.class),
                examples = @ExampleObject(name = "walk", summary = "도보 후보", value = BoardingOptionsApiDocsExamples.WALK))),
        @ApiResponse(responseCode = "304", description = "ETag와 같은 내용. 본문 없음", content = @Content),
        @ApiResponse(responseCode = "400", description = "노선 ID·정류장 ID·쿼리 형식 오류 또는 지원하지 않는 이동 수단",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "invalidRouteId", value = ApiDocsExamples.INVALID_ROUTE),
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST),
                    @ExampleObject(name = "unsupportedMode", value = BoardingOptionsApiDocsExamples.UNSUPPORTED_MODE)
                })),
        @ApiResponse(responseCode = "404", description = "현재 카탈로그에 없는 노선 또는 그 노선에 없는 정류장",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "routeNotFound", value = ApiDocsExamples.ROUTE_NOT_FOUND),
                    @ExampleObject(name = "stationNotFound", value = ApiDocsExamples.STATION_NOT_FOUND)
                })),
        @ApiResponse(responseCode = "409", description = "판본이 현재와 다르거나, stopSequence가 그 정류장의 방문이 아니거나, "
            + "반복 방문인데 stopSequence가 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "referenceVersionConflict", value = ApiDocsExamples.REFERENCE_VERSION_CONFLICT),
                    @ExampleObject(name = "stopVisitAmbiguous", value = BoardingOptionsApiDocsExamples.STOP_VISIT_AMBIGUOUS)
                })),
        @ApiResponse(responseCode = "503", description = "저장소 조회 불가. no-store",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.SERVICE_UNAVAILABLE))),
        @ApiResponse(responseCode = "405", description = "지원하지 않는 HTTP 메서드",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.METHOD_NOT_ALLOWED))),
        @ApiResponse(responseCode = "500", description = "요청 처리 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INTERNAL_ERROR)))
    })
    public ResponseEntity<BoardingOptionsResponse> get(
        @Parameter(description = "9자리 GBIS 노선 ID", example = "\"204000057\"", schema = @Schema(pattern = "^[0-9]{9}$"))
        @PathVariable String routeId,
        @Parameter(description = "기준 정류장 ID", example = "\"205000231\"", schema = @Schema(pattern = "^[0-9]{1,20}$"))
        @PathVariable String stationId,
        @Parameter(description = "쓸 수 있는 구간 수단. 쉼표로 여러 개. 지금은 WALK만. 기본값은 앞으로도 WALK", schema = @Schema(defaultValue = "WALK"))
        @RequestParam(defaultValue = "WALK") String modes,
        @Parameter(description = "이동 방법 전체 시간 상한(분)",
            schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "30", defaultValue = "15"))
        @RequestParam(defaultValue = "15") int maxMinutes,
        @Parameter(description = "최대 후보 정류장 수",
            schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50", defaultValue = "10"))
        @RequestParam(defaultValue = "10") int limit,
        @Parameter(description = "고를 관계. 쉼표로 여러 개(BEFORE, AFTER, OPPOSITE, SAME_STATION). 없으면 전부",
            example = "BEFORE,OPPOSITE")
        @RequestParam(required = false) String relations,
        @Parameter(description = "승차 불가 정류장 제외", schema = @Schema(type = "boolean", defaultValue = "true"))
        @RequestParam(defaultValue = "true") boolean boardingOnly,
        @Parameter(description = "반복 방문일 때 기준 방문 순번",
            schema = @Schema(type = "integer", format = "int32", minimum = "1"))
        @RequestParam(required = false) Integer stopSequence,
        @Parameter(description = "현재 노선 판본. 양의 64비트 정수의 문자열 표현", example = "\"7\"",
            schema = @Schema(implementation = String.class, pattern = "^[1-9][0-9]*$"))
        @RequestParam(required = false) Long referenceVersionId,
        WebRequest request
    ) {
        BoardingOptionsResponse response = BoardingOptionsResponse.from(service.get(new BoardingOptionsQuery(
            new RouteId(routeId), stationId, stopSequence, referenceVersionId, modes(modes), maxMinutes, limit,
            relations(relations), boardingOnly)));
        String etag = "W/\"b-" + Integer.toHexString(response.hashCode()) + "\"";
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(CACHE).eTag(etag).build();
        }
        return ResponseEntity.ok().cacheControl(CACHE).eTag(etag).body(response);
    }

    private static Set<AccessMode> modes(String raw) {
        Set<AccessMode> modes = EnumSet.noneOf(AccessMode.class);
        for (String value : Arrays.stream(raw.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList()) {
            try {
                modes.add(AccessMode.valueOf(value));
            } catch (IllegalArgumentException unknown) {
                throw new BoardingOptionsException(ErrorCode.UNSUPPORTED_MODE);
            }
        }
        return modes;
    }

    private static Set<StopRelation> relations(String raw) {
        if (raw == null) {
            return EnumSet.allOf(StopRelation.class);
        }
        Set<StopRelation> relations = EnumSet.noneOf(StopRelation.class);
        for (String value : Arrays.stream(raw.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList()) {
            try {
                relations.add(StopRelation.valueOf(value));
            } catch (IllegalArgumentException unknown) {
                throw new BoardingOptionsException(ErrorCode.INVALID_REQUEST);
            }
        }
        if (relations.isEmpty()) {
            throw new BoardingOptionsException(ErrorCode.INVALID_REQUEST);
        }
        return relations;
    }
}
