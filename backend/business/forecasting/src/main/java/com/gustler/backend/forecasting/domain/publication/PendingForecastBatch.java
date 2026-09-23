package com.gustler.backend.forecasting.domain.publication;

import java.time.Instant;

/** 아직 예보가 발행되지 않은 수집 배치와 조회 당시의 수집 시도. */
public record PendingForecastBatch(
    long observationBatchId,
    long routeVersionId,
    long routeId,
    Instant responseReceivedAt,
    int attemptNumber
) {
}
