package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;

/** 이미 예보 입력으로 확정된 수집 배치는 재수집할 수 없음을 알린다. */
public class CollectionInputConfirmedException extends RuntimeException {

    public CollectionInputConfirmedException(final long batchId, OffsetDateTime confirmedAt) {
        super("입력으로 확정한 수집 배치는 다시 열 수 없다: batch=%d, confirmedAt=%s"
            .formatted(batchId, confirmedAt));
    }
}
