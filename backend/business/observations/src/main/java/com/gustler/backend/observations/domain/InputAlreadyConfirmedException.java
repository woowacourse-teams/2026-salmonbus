package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;

public class InputAlreadyConfirmedException extends RuntimeException {
    public InputAlreadyConfirmedException(final long batchId, OffsetDateTime confirmedAt) {
        super("입력으로 확정한 수집 배치는 다시 열 수 없다: batch=%d, confirmedAt=%s"
            .formatted(batchId, confirmedAt));
    }
}
