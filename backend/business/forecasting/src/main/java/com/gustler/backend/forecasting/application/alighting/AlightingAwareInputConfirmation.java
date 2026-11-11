package com.gustler.backend.forecasting.application.alighting;

import java.time.Instant;

/** 계산 전에 입력을 한 번 확정한다. 빈 배치나 허용된 확정 시각을 벗어난 배치는 false를 반환한다. */
public interface AlightingAwareInputConfirmation {
    boolean confirm(long batchId, Instant at);
}
