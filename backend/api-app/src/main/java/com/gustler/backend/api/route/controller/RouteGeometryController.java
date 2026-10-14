package com.gustler.backend.api.route.controller;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.application.RouteGeometryQueryService;
import com.gustler.backend.api.route.dto.RouteGeometryResponse;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
public class RouteGeometryController {
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().mustRevalidate();
    private final RouteGeometryQueryService service;

    public RouteGeometryController(RouteGeometryQueryService service) { this.service = service; }

    @GetMapping("/api/v2/routes/{routeId}/geometry")
    public ResponseEntity<RouteGeometryResponse> get(@PathVariable String routeId,
        @RequestParam(required = false) Long referenceVersionId, WebRequest request) {
        var response = RouteGeometryResponse.from(service.get(new RouteId(routeId), referenceVersionId));
        if (response.status().equals("UNAVAILABLE")) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
        String etag = "W/\"g-" + response.geometryRevision() + "\"";
        if (request.checkNotModified(etag)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(CACHE).eTag(etag).build();
        return ResponseEntity.ok().cacheControl(CACHE).eTag(etag).body(response);
    }
}
