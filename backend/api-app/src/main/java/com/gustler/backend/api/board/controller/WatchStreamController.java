package com.gustler.backend.api.board.controller;

import com.gustler.backend.api.route.RouteId;
import io.swagger.v3.oas.annotations.Hidden;
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
 * 쥐므로, DB 를 읽지 않고 노선이 있는지도 확인하지 않는다.
 *
 * <p>계약 문서({@code contract/})가 아직 없어서 API 문서에서는 숨긴다.
 */
@RestController
@Hidden
@ConditionalOnProperty(name = WatchStreamConfiguration.ENABLED, havingValue = "true")
public class WatchStreamController {
    private static final String PATH = "/api/v1/routes/{routeId}/watch/events";

    private static final CacheControl NOT_STORED_NOT_TRANSFORMED = CacheControl.noStore().noTransform();

    private final WatchStreamRegistry registry;

    WatchStreamController(WatchStreamRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(PATH)
    public ResponseEntity<SseEmitter> open(@PathVariable String routeId) {
        return registry.open(new RouteId(routeId))
            .map(stream -> ResponseEntity.ok().cacheControl(NOT_STORED_NOT_TRANSFORMED).body(stream))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 이 처리기가 없으면 Spring 이 HEAD 를 GET 처리기로 보내 스트림이 열린다. HEAD 응답은 본문이 나가지 않아 ping 이 소켓에
     * 닿지 않는다. 그렇게 열린 연결은 상대가 끊어도 전송이 실패하지 않아서, 수명이 다할 때까지 연결 자리 하나를 쥔다.
     */
    @RequestMapping(path = PATH, method = RequestMethod.HEAD)
    public ResponseEntity<Void> describe(@PathVariable String routeId) {
        new RouteId(routeId);
        return ResponseEntity.ok()
            .cacheControl(NOT_STORED_NOT_TRANSFORMED)
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .build();
    }
}
