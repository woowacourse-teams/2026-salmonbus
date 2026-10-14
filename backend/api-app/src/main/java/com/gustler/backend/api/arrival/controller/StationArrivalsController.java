package com.gustler.backend.api.arrival.controller;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.dto.StationArrivalsResponse;
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
    public ResponseEntity<StationArrivalsResponse> get(@PathVariable String stationId,
        @RequestParam(required = false) String routeId,
        @RequestParam(required = false) Long referenceVersionId,
        @RequestParam(required = false) Integer stopSequence) {
        var overview = service.get(new ArrivalQuery(stationId, routeId, referenceVersionId, stopSequence));
        CacheControl cache = CacheControl.noStore();
        if (overview.status() != ArrivalAvailability.UNAVAILABLE) {
            cache = CacheControl.maxAge(overview.cacheMaxAge()).cachePublic();
        }
        return ResponseEntity.ok().cacheControl(cache).body(StationArrivalsResponse.from(overview));
    }
}
