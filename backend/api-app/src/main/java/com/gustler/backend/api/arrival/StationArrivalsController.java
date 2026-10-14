package com.gustler.backend.api.arrival;

import java.time.Duration;
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
        var response = service.get(new ArrivalQuery(stationId, routeId, referenceVersionId, stopSequence));
        CacheControl cache = CacheControl.noStore();
        if (!response.status().equals("UNAVAILABLE")) {
            long remaining = Duration.between(response.generatedAt(), response.snapshot().staleAt()).toSeconds();
            long untilRefresh = Duration.between(response.generatedAt(), response.snapshot().refreshAfterAt()).toSeconds();
            cache = CacheControl.maxAge(Duration.ofSeconds(Math.max(0, Math.min(5, Math.min(remaining, untilRefresh)))))
                .cachePublic();
        }
        return ResponseEntity.ok().cacheControl(cache).body(response);
    }
}
