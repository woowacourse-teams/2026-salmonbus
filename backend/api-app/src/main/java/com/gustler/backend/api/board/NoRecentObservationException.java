package com.gustler.backend.api.board;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;
import java.time.Duration;

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
