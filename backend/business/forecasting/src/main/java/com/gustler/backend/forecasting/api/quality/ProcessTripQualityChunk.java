package com.gustler.backend.forecasting.api.quality;

import java.time.Instant;

/**
 * 지정한 관측 범위의 품질 조사를 재개 가능한 제한된 묶음으로 실행한다.
 */
public interface ProcessTripQualityChunk {
    /**
     * 지정 시각까지 최대 batchLimit개 묶음의 품질 정리를 진행한다. 처리 건수와 남은 작업 상태를 함께 반환한다.
     */
    TripQualityChunkResult applyChunk(long routeVersionId, Instant until, int batchLimit);
}
