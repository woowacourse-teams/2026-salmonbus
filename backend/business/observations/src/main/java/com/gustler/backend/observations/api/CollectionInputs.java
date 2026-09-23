package com.gustler.backend.observations.api;

import java.time.Instant;

/** 예보와 평가가 사용하는 관측 입력을 같은 트랜잭션에서 조회하고 확정한다. */
public interface CollectionInputs {
    CollectionInput lockForForecast(long batchId);
    CollectionInput lockForObservation(long observationId);
    void confirmInput(long batchId, int attemptNumber, Instant confirmedAt);
}
