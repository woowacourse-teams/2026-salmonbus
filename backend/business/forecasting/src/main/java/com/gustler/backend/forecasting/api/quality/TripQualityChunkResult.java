package com.gustler.backend.forecasting.api.quality;

/** waitingForObservations가 참이면 추가 관측이 저장된 후 같은 명령으로 재개한다. */
public record TripQualityChunkResult(int processedBatches, boolean discoveryCompleted, boolean completed,
    boolean waitingForObservations) { }
