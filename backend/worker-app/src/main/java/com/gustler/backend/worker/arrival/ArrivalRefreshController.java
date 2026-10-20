package com.gustler.backend.worker.arrival;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Request;
import com.gustler.backend.arrivals.api.RefreshStationArrivals;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 인증과 대기 한도를 검증한 뒤 정류장 수집을 요청한다.
 * 접수 응답과 제한된 완료 대기를 구분한다.
 */
@RestController
@ConditionalOnProperty(prefix = "arrivals", name = "enabled", havingValue = "true")
public class ArrivalRefreshController {
    private final RefreshStationArrivals coordinator;
    private final byte[] token;

    public ArrivalRefreshController(RefreshStationArrivals coordinator, ArrivalProperties properties) {
        this.coordinator = coordinator;
        this.token = properties.internalToken().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 내부 토큰과 최대 대기 시간을 검증한 뒤 갱신을 요청한다. 인증 실패·잘못된 입력을 HTTP 상태로 구별하고 응답은 캐시하지 않는다.
     */
    @PostMapping("/internal/arrivals/refresh")
    public ResponseEntity<ArrivalRefresh> refresh(
        @RequestHeader(name = "X-Arrival-Token", defaultValue = "") String supplied,
        @RequestBody Request request,
        @RequestHeader(name = ArrivalRefresh.WAIT_HEADER, defaultValue = "0") final int maximumWaitMillis) {
        if (!MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (maximumWaitMillis < 0 || maximumWaitMillis > ArrivalRefresh.MAX_WAIT_MILLIS) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(requestRefresh(request.stationId(), maximumWaitMillis));
        }
        catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        }
    }

    private ArrivalRefresh requestRefresh(String stationId, final int maximumWaitMillis) {
        if (maximumWaitMillis == 0) {
            return coordinator.request(stationId);
        }
        return coordinator.requestAndAwait(stationId, Duration.ofMillis(maximumWaitMillis));
    }
}
