package com.gustler.backend.forecasting.application.les;

import java.time.Instant;

/** 계산 전에 입력을 한 번 확정한다. 빈 성공 배치는 false를 반환한다. */
public interface LesInputConfirmation {
    boolean confirm(long batchId, Instant at);
}
