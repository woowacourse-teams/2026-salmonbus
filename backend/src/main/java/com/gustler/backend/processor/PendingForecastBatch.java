package com.gustler.backend.processor;

import java.time.Instant;

/**
 * 예보가 아직 안 붙은 판.
 *
 * <p>/board 는 forecast_completed_at 이 찍힌 판만 본다. 그 열을 채우는 것이 예보 쪽 몫이라,
 * 무엇이 아직 안 됐는지가 이 값으로 나온다.
 */
public record PendingForecastBatch(
    long observationBatchId,
    long routeVersionId,
    Instant responseReceivedAt
) {
}
