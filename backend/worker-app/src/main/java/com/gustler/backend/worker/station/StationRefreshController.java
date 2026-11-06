package com.gustler.backend.worker.station;

import com.gustler.backend.station.contract.StationTileRefresh;
import com.gustler.backend.stations.api.RequestStationTileRefresh;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 토큰을 확인하고 영속 큐에 접수한다. 실제 원천 수집 완료는 기다리지 않는다.
 *
 * <p>새로 접수하면 이 worker의 채우기 유휴 대기를 끝낸다.
 */
@RestController
@ConditionalOnProperty(prefix = "stations.fill", name = "enabled", havingValue = "true")
public class StationRefreshController {

    private final RequestStationTileRefresh refresh;
    private final StationFillScheduler scheduler;
    private final byte[] token;

    public StationRefreshController(
        RequestStationTileRefresh refresh,
        StationFillScheduler scheduler,
        @Value("${stations.fill.internal-token:}") String token
    ) {
        if (token.isBlank()) {
            throw new IllegalArgumentException("정류장 내부 수집 인증 토큰이 필요하다");
        }
        this.refresh = refresh;
        this.scheduler = scheduler;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/stations/refresh")
    public ResponseEntity<StationTileRefresh> refresh(
        @RequestHeader(name = "X-Station-Token", defaultValue = "") String supplied,
        @RequestBody StationTileRefresh.Request request
    ) {
        if (!MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        StationTileRefresh result;
        try {
            result = refresh.request(request.tile());
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        }
        if (result.status() == StationTileRefresh.Status.QUEUED) {
            scheduler.wake();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
