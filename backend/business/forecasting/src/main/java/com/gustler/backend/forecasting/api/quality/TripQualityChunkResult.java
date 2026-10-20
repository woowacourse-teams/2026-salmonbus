package com.gustler.backend.forecasting.api.quality;

/**
 * 제한된 품질 조사 회차의 처리 배치 수와 발견·조사 완료 여부다.
 */
public record TripQualityChunkResult(int processedBatches, boolean discoveryCompleted, boolean completed) {
}
