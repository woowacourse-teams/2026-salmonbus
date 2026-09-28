package com.gustler.backend.observations.api;

import java.time.Instant;

/** 예보가 끝난 수집 배치를 입력으로 확정한다. 확정한 배치는 다시 수집하지 않는다. */
public interface CollectionInputs {
    void confirmInput(long batchId, Instant confirmedAt);
}
