package com.gustler.backend.forecasting.api.quality;

import java.time.Instant;

/**
 * 차량 조사의 단계·근거·재개 위치·해제 가능 여부를 유지보수 조회에 전달한다.
 */
public record TripQualityStatus(String vehicleId, String phase, boolean completed, Long evidenceObservationId,
    Instant lastBatchAt, long lastBatchId, boolean canRelease, Instant startedAt, Instant investigatedAt) {
}
