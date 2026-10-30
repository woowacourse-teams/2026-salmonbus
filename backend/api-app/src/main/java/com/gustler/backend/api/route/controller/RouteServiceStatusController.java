package com.gustler.backend.api.route.controller;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.application.RouteServiceStatusQuery;
import com.gustler.backend.api.route.domain.RouteServiceStatus;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routes")
public class RouteServiceStatusController {
    private final RouteServiceStatusQuery query;

    public RouteServiceStatusController(RouteServiceStatusQuery query) {
        this.query = query;
    }

    @Operation(
        summary = "노선 서비스 운영상태",
        description = "관측·모델이 없어도 운영 안내를 반환합니다. 요청 시각 기준 30초간 유효하며 캐시하지 않습니다.")
    @GetMapping("/{routeId}/service-status")
    public ResponseEntity<RouteServiceStatus> get(@PathVariable String routeId) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(query.get(new RouteId(routeId).value()));
    }
}
