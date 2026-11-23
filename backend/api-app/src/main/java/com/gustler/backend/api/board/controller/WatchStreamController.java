package com.gustler.backend.api.board.controller;

import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.route.RouteId;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 판정보드 화면이 "새 관측이 생겼다"는 신호를 기다리는 SSE 연결을 연다. 스트림에 데이터는 싣지 않는다. 화면은 신호를 받으면
 * {@code /watch} 를 다시 부른다.
 *
 * <p>답은 넷이다. 연결을 받으면 200 과 이벤트 스트림, 연결 상한에 닿았거나 서버가 내려가는 중이면 본문 없는 204, 노선 ID 형식이
 * 틀리면 {@code /watch} 와 같은 400 이다. {@code watch.stream.enabled} 가 꺼져 있으면 이 컨트롤러가 없어서 다른 없는 경로와
 * 같은 404 다. 204 를 받은 EventSource 는 다시 연결하지 않고, 화면은 폴링으로 계속 동작한다. 403 은 CloudFront 가 200 과
 * index.html 로 바꾸므로 쓰지 않는다.
 *
 * <p>이 요청은 스트림이 끝날 때까지 열려 있다. 요청 스레드에서 DB 를 한 번이라도 읽으면 OSIV 가 그 DB 연결을 스트림이 끝날 때까지
 * 쥐므로, DB 를 읽지 않고 노선이 있는지도 확인하지 않는다. 그 노선의 현재 관측은 변경 감지기가 자기 스레드에서 읽어 알린다.
 *
 * <p>이벤트의 모양과 순서는 {@code contract/route-watch-events-v1.md} 에 있다.
 */
@RestController
@Tag(name = ApiDocsTags.ROUTES)
@ConditionalOnProperty(name = WatchStreamConfiguration.ENABLED, havingValue = "true")
public class WatchStreamController {
    private static final String PATH = "/api/v1/routes/{routeId}/watch/events";

    private static final CacheControl NOT_STORED_NOT_TRANSFORMED = CacheControl.noStore().noTransform();

    private final WatchStreamRegistry registry;

    WatchStreamController(WatchStreamRegistry registry) {
        this.registry = registry;
    }

    /**
     * 매핑에 {@code produces} 를 달지 않는다. 달면 {@code Accept} 가 맞지 않는 요청이 400 대신 406 으로 끝난다. 그래서 문서의
     * 응답 형식은 아래 어노테이션에 직접 적는다. springdoc 은 {@code SseEmitter} 를 풀어 주지 않는다.
     */
    @GetMapping(PATH)
    @Operation(operationId = "getRouteWatchEvents", summary = "노선별 판정 보드의 새 관측 신호",
        description = "판정 보드가 기준으로 삼는 관측이 달라지면 observation 이벤트로 그 관측 시각만 알리는 SSE 스트림입니다. "
            + "보드 데이터는 싣지 않습니다. 화면은 신호의 observedAt 이 가진 값보다 새로우면 /watch 를 다시 조회하고, 폴링은 그대로 "
            + "둡니다. 연결 직후에 retry 와 주석 한 줄이 오고, 서버가 그 노선의 현재 관측을 알고 있으면 observation 이 바로 "
            + "이어집니다. 모르면 다음 확인(기본 1초 간격) 뒤에 옵니다. 15초마다 ping 이벤트가 옵니다. 이벤트의 모양과 보장은 "
            + "contract/route-watch-events-v1.md 에 있습니다. 서버 설정으로 꺼져 있으면 이 주소는 404 이고 이 문서에도 나오지 "
            + "않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "연결됨. 스트림은 서버가 닫을 때까지(기본 30분) 열려 있다",
            headers = @Header(name = "Cache-Control", description = "no-store, no-transform",
                schema = @Schema(type = "string")),
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "204", description = "연결 수가 상한에 닿았거나 서버가 내려가는 중. 본문 없음",
            content = @Content),
        @ApiResponse(responseCode = "400", description = "노선 ID 형식이 올바르지 않음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(value = ApiDocsExamples.INVALID_ROUTE)))
    })
    public ResponseEntity<SseEmitter> open(
        @Parameter(description = "9자리 GBIS 노선 ID", example = "123456789")
        @PathVariable String routeId
    ) {
        return registry.open(new RouteId(routeId))
            .map(stream -> ResponseEntity.ok().cacheControl(NOT_STORED_NOT_TRANSFORMED).body(stream))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 이 처리기가 없으면 Spring 이 HEAD 를 GET 처리기로 보내 스트림이 열린다. HEAD 응답은 본문이 나가지 않아 ping 이 소켓에
     * 닿지 않는다. 그렇게 열린 연결은 상대가 끊어도 전송이 실패하지 않아서, 수명이 다할 때까지 연결 자리 하나를 쥔다.
     *
     * <p>화면은 HEAD 를 보내지 않으므로 API 문서에서는 숨긴다. 숨기지 않으면 springdoc 이 head 동작으로 싣는다.
     */
    @Hidden
    @RequestMapping(path = PATH, method = RequestMethod.HEAD)
    public ResponseEntity<Void> describe(@PathVariable String routeId) {
        new RouteId(routeId);
        return ResponseEntity.ok()
            .cacheControl(NOT_STORED_NOT_TRANSFORMED)
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .build();
    }
}
