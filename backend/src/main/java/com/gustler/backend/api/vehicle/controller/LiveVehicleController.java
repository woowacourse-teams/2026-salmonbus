package com.gustler.backend.api.vehicle.controller;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.vehicle.application.LiveVehicleOverview;
import com.gustler.backend.api.vehicle.application.LiveVehicleQueryService;
import com.gustler.backend.api.vehicle.dto.LiveVehicleResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routes")
public class LiveVehicleController {

    private final LiveVehicleQueryService liveVehicleQueryService;

    public LiveVehicleController(final LiveVehicleQueryService liveVehicleQueryService) {
        this.liveVehicleQueryService = liveVehicleQueryService;
    }

    @GetMapping("/{routeId}/vehicles")
    public ResponseEntity<LiveVehicleResponse> getLiveVehicles(
        @PathVariable("routeId") final String routeId
    ) {
        final LiveVehicleOverview overview = liveVehicleQueryService.getLiveVehicles(
            new RouteId(routeId)
        );

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(overview.cacheMaxAge()).cachePublic())
            .body(LiveVehicleResponse.from(overview));
    }
}
