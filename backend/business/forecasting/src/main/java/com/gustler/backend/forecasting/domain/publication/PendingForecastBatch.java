package com.gustler.backend.forecasting.domain.publication;

import java.time.Instant;

/** 아직 예보가 발행되지 않은 수집 배치. */
public record PendingForecastBatch(
    long observationBatchId,
    long routeVersionId,
    long routeId,
    Instant responseReceivedAt
) {
}
