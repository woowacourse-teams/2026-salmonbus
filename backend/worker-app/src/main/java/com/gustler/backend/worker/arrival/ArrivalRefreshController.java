package com.gustler.backend.worker.arrival;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrivals.api.RefreshStationArrivals;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "arrivals", name = "enabled", havingValue = "true")
public class ArrivalRefreshController {
    private final RefreshStationArrivals coordinator;
    private final byte[] token;

    public ArrivalRefreshController(RefreshStationArrivals coordinator, ArrivalProperties properties) {
        this.coordinator = coordinator;
        this.token = properties.internalToken().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/arrivals/refresh")
    public ResponseEntity<ArrivalRefresh> refresh(
        @RequestHeader(name = "X-Arrival-Token", defaultValue = "") String supplied,
        @RequestBody ArrivalRefresh.Request request,
        @RequestHeader(name = ArrivalRefresh.WAIT_HEADER, defaultValue = "0") final int maximumWaitMillis) {
        if (!MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (maximumWaitMillis < 0 || maximumWaitMillis > ArrivalRefresh.MAX_WAIT_MILLIS) {
            return ResponseEntity.badRequest().build();
        }
        try { return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
            .body(maximumWaitMillis == 0 ? coordinator.request(request.stationId())
                : coordinator.requestAndAwait(request.stationId(), Duration.ofMillis(maximumWaitMillis))); }
        catch (IllegalArgumentException invalid) { return ResponseEntity.badRequest().build(); }
    }
}
