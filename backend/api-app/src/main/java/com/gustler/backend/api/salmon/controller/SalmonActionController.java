package com.gustler.backend.api.salmon.controller;

import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.salmon.application.SalmonActionService;
import com.gustler.backend.api.salmon.application.SalmonActionService.SalmonActionAnswer;
import com.gustler.backend.api.salmon.dto.SalmonActionResponse;
import com.gustler.backend.api.salmon.dto.SalmonActionResponse.Kind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 선택 정류장의 연어행동 종료 상태를 조회한다. */
@RestController
@Tag(name = ApiDocsTags.ROUTES)
public class SalmonActionController {
    private final SalmonActionService service;

    public SalmonActionController(SalmonActionService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/routes/{routeId}/stations/{stationId}/salmon-action")
    @Operation(operationId = "getSalmonAction", summary = "연어행동 판정 안내",
        description = "기능이 꺼져 있으면 kind만 DISABLED다. 켜져 있고 비교 근거가 없으면 근거 부족 문장을 반환한다. 확률은 반환하지 않는다.")
    public ResponseEntity<SalmonActionResponse> show(
        @PathVariable String routeId,
        @PathVariable String stationId,
        @RequestParam int stopSequence
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(response(service.query(routeId, stationId, stopSequence)));
    }

    private static SalmonActionResponse response(SalmonActionAnswer answer) {
        return switch (answer) {
            case SalmonActionAnswer.Off ignored -> new SalmonActionResponse(Kind.DISABLED, null, null);
            case SalmonActionAnswer.Decided decided ->
                new SalmonActionResponse(Kind.DECIDED, decided.terminal().name(), decided.guidance());
        };
    }
}
