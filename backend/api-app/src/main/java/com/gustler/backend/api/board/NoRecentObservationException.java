package com.gustler.backend.api.board;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;
import java.time.Duration;

/**
 * 조회에 사용할 유효한 관측을 찾지 못한 상태를 API 오류 경계에 알린다.
 */
public class NoRecentObservationException extends ApiException {
    public NoRecentObservationException() {
        super(ErrorCode.NO_RECENT_OBSERVATION);
    }

    public NoRecentObservationException(
        Duration retryAfter
    ) {
        super(ErrorCode.NO_RECENT_OBSERVATION, retryAfter);
    }
}
